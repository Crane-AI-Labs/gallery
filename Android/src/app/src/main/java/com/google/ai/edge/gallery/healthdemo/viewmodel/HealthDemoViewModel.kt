package com.google.ai.edge.gallery.healthdemo.viewmodel

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.healthdemo.data.AgeRange
import com.google.ai.edge.gallery.healthdemo.data.DurationUnit
import com.google.ai.edge.gallery.healthdemo.data.HealthGuidance
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import com.google.ai.edge.gallery.healthdemo.data.Sex
import com.google.ai.edge.gallery.healthdemo.data.ClinicalPrompt
import com.google.ai.edge.gallery.healthdemo.data.GuidanceValidator
import com.google.ai.edge.gallery.healthdemo.data.ImagePreprocessor
import com.google.ai.edge.gallery.healthdemo.data.MedAsrEngine
import com.google.ai.edge.gallery.healthdemo.data.TraditionalMedicine
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import com.google.ai.edge.gallery.llm.DeviceInfo
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.Context
import com.google.ai.edge.gallery.analytics.HealthDemoAnalytics
import com.google.ai.edge.gallery.data.ModelAssetManager
import com.google.ai.edge.gallery.healthdemo.data.CapturedLocation
import com.google.ai.edge.gallery.healthdemo.data.LocationCapture
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.ClinicianConfirmation
import com.google.ai.edge.gallery.healthdemo.data.PauseReason
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HealthDemoViewModel"
private const val N_CTX = 2048
private const val N_GPU_LAYERS = 99  // offload as many layers as possible to GPU

data class HealthDemoUiState(
    // Role selection
    val role: PatientRole? = null,
    val customRole: String = "",

    // Patient assessment form
    val sessionStartTime: Long = 0L,
    val symptoms: String = "",
    val durationValue: String = "",
    val durationUnit: DurationUnit = DurationUnit.Days,
    val ageYears: String = "",
    val ageMonths: String = "",
    val age: AgeRange? = null,
    val sex: Sex? = null,
    val vitalSigns: VitalSigns = VitalSigns(),
    val capturedImageBytes: ByteArray? = null,
    val capturedLocation: CapturedLocation? = null,

    // Signs & Symptoms
    val checkedSigns: Set<String> = emptySet(),
    val confirmedSigns: Set<String> = emptySet(),
    val dangerSignsReviewed: Boolean = false,
    val pendingSignAlert: String? = null,
    val pendingSignIsDanger: Boolean = false,

    // Traditional medicine
    val traditionalMedicine: TraditionalMedicine? = null,

    // Guidance result
    val guidance: HealthGuidance? = null,

    // Clinician confirmation fields (embedded in guidance screen)
    val clinicianAcknowledged: Boolean = false,
    val treatmentAdministered: String = "",

    // Saved assessment (set after save)
    val savedAssessment: SavedAssessment? = null,

    // Legacy danger sign index (unused in new flow)
    val dangerSignIndex: Int = 0,

    // Clinician confirmation (set on ClinicianConfirmationScreen)
    val clinicianConfirmation: ClinicianConfirmation? = null,

    // Referral info (set on ReferralScreen)
    val referralInfo: ReferralInfo? = null,

    // Processing state
    val isProcessing: Boolean = false,
    val processingStatus: String = "",
    val inferenceError: String? = null,

    // Voice note state
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
)

@HiltViewModel
class HealthDemoViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthDemoUiState())
    val uiState = _uiState.asStateFlow()

    private var modelHandle: Long = 0L
    private var visionLoaded: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: kotlinx.coroutines.Job? = null

    fun setRole(role: PatientRole) {
        _uiState.update { it.copy(role = role, customRole = "") }
        HealthDemoAnalytics.logRoleSelected(role.name)
    }

    fun setCustomRole(text: String) {
        _uiState.update { it.copy(customRole = text) }
    }

    /** Call when entering the symptoms screen — starts GPS early so it's ready by inference time */
    fun startLocationCapture() {
        if (_uiState.value.capturedLocation != null) return  // already captured
        viewModelScope.launch(Dispatchers.IO) {
            val location = LocationCapture.capture(appContext)
            if (location != null) {
                _uiState.update { it.copy(capturedLocation = location) }
            }
        }
    }

    fun markSessionStart() {
        if (_uiState.value.sessionStartTime == 0L) {
            _uiState.update { it.copy(sessionStartTime = System.currentTimeMillis()) }
        }
    }

    fun setSymptoms(symptoms: String) {
        _uiState.update { it.copy(symptoms = symptoms) }
    }

    fun setAgeInput(years: String, months: String) {
        val y = years.toIntOrNull() ?: 0
        val m = months.toIntOrNull()?.coerceIn(0, 11) ?: 0
        val group = if (years.isBlank() && months.isBlank()) null
                    else com.google.ai.edge.gallery.healthdemo.data.ageGroupFromInput(y, m)
        _uiState.update { it.copy(ageYears = years, ageMonths = months, age = group) }
    }

    fun setSex(sex: Sex) {
        _uiState.update { it.copy(sex = sex) }
    }

    fun setTraditionalMedicine(tm: TraditionalMedicine) {
        _uiState.update { it.copy(traditionalMedicine = tm) }
    }

    fun setDangerSignsReviewed(reviewed: Boolean) {
        _uiState.update { it.copy(dangerSignsReviewed = reviewed) }
    }

    fun setClinicalAcknowledged(ack: Boolean) {
        _uiState.update { it.copy(clinicianAcknowledged = ack) }
    }

    fun setTreatmentAdministered(text: String) {
        _uiState.update { it.copy(treatmentAdministered = text) }
    }

    fun setCapturedImage(imageBytes: ByteArray?) {
        _uiState.update { it.copy(capturedImageBytes = imageBytes) }
        if (imageBytes != null) HealthDemoAnalytics.logImageCaptured()
    }

    private var recordedBytes = java.io.ByteArrayOutputStream()

    fun startVoiceRecording() {
        HealthDemoAnalytics.logVoiceNoteUsed()
        startRecordingInternal()
    }

    fun stopVoiceRecording() {
        stopRecordingInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingInternal() {
        recordedBytes = java.io.ByteArrayOutputStream()
        val bufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        audioRecord?.startRecording()
        _uiState.update { it.copy(isRecording = true) }

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            val maxBytes = 16000 * 2 * 30 // 30s max
            while (_uiState.value.isRecording && recordedBytes.size() < maxBytes) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    recordedBytes.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopRecordingInternal() {
        _uiState.update { it.copy(isRecording = false) }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        viewModelScope.launch(Dispatchers.IO) {
            recordingJob?.join()
            recordingJob = null

            val pcmBytes = recordedBytes.toByteArray()
            if (pcmBytes.isEmpty()) return@launch

            _uiState.update { it.copy(isTranscribing = true) }
            Log.d(TAG, "Transcribing ${pcmBytes.size} bytes of audio")

            try {
                MedAsrEngine.setModelPath(
                    AppSettings.getAsrModelPath(appContext)
                        ?: ModelAssetManager.getModelPath(appContext, ModelAssetManager.ASR_MODEL)
                )
                MedAsrEngine.setTokenizerPath(
                    AppSettings.getTokenizerPath(appContext)
                        ?: ModelAssetManager.getModelPath(appContext, ModelAssetManager.ASR_TOKENIZER)
                )
                val transcript = MedAsrEngine.transcribe(pcmBytes)
                if (transcript.isNotBlank()) {
                    _uiState.update { state ->
                        val existing = state.symptoms.trim()
                        val separator = if (existing.isNotEmpty()) ". " else ""
                        state.copy(
                            symptoms = existing + separator + transcript.trim(),
                            isTranscribing = false,
                        )
                    }
                    Log.d(TAG, "Transcription complete: ${transcript.length} chars")
                } else {
                    _uiState.update { it.copy(isTranscribing = false) }
                    Log.w(TAG, "Empty transcription result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _uiState.update { it.copy(isTranscribing = false, inferenceError = "Transcription failed: ${e.message}") }
            }
        }
    }

    fun setVitalSigns(vitalSigns: VitalSigns) {
        _uiState.update { it.copy(vitalSigns = vitalSigns) }
    }

    fun setDuration(value: String) {
        _uiState.update { it.copy(durationValue = value) }
    }

    fun setDurationUnit(unit: DurationUnit) {
        _uiState.update { it.copy(durationUnit = unit) }
    }

    // ─── Signs & Symptoms ────────────────────────────────────────────────────────

    fun checkSign(sign: String, isDanger: Boolean) {
        _uiState.update { it.copy(
            checkedSigns = it.checkedSigns + sign,
            pendingSignAlert = sign,
            pendingSignIsDanger = isDanger
        )}
    }

    fun uncheckSign(sign: String) {
        _uiState.update { it.copy(
            checkedSigns = it.checkedSigns - sign,
            confirmedSigns = it.confirmedSigns - sign,
            pendingSignAlert = null
        )}
    }

    fun confirmSign() {
        val sign = _uiState.value.pendingSignAlert ?: return
        _uiState.update { it.copy(
            confirmedSigns = it.confirmedSigns + sign,
            pendingSignAlert = null
        )}
    }

    fun dismissSignForNow() {
        _uiState.update { it.copy(pendingSignAlert = null) }
    }

    /**
     * Run MedGemma inference to generate clinical guidance.
     * Retries once on parse failure (common on cold KV cache).
     * Never shows fabricated fallback data — if both attempts fail,
     * shows an honest error asking the health worker to try again.
     */
    fun getGuidance() {
        // Prevent concurrent inference — native code is not thread-safe
        if (_uiState.value.isProcessing) return

        val state = _uiState.value
        _uiState.update { it.copy(isProcessing = true, inferenceError = null) }

        HealthDemoAnalytics.logAssessmentStarted(appContext)

        viewModelScope.launch(Dispatchers.IO.limitedParallelism(1)) {
            val startMs = System.currentTimeMillis()
            try {
                val result = runMedGemmaInferenceWithRetry(state)
                val durationMs = System.currentTimeMillis() - startMs
                if (result != null) {
                    HealthDemoAnalytics.logInferenceCompleted(
                        appContext, durationMs, hasImage = state.capturedImageBytes != null
                    )
                    _uiState.update { it.copy(guidance = result, savedAssessment = null, isProcessing = false) }
                } else {
                    HealthDemoAnalytics.logInferenceFailed("parse_failed_after_retry")
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            inferenceError = "Could not generate a valid assessment. Please try again."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MedGemma inference failed", e)
                HealthDemoAnalytics.logInferenceFailed(e.message ?: "unknown")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        inferenceError = "Unable to generate assessment. Please try again."
                    )
                }
            }
        }
    }

    /**
     * Attempt inference, retry once if the output can't be parsed.
     * Returns null if both attempts fail — never returns fabricated data.
     */
    private fun runMedGemmaInferenceWithRetry(state: HealthDemoUiState): HealthGuidance? {
        val confirmedSigns = state.confirmedSigns

        // First attempt
        val firstResult = runMedGemmaInference(state)
        // Safe diagnostic: log format signature without PHI
        val firstPrefix = firstResult.take(40).replace(Regex("[a-zA-Z]{4,}"), "***")
        Log.d(TAG, "Attempt 1: ${firstResult.length} chars, starts with: $firstPrefix")
        val firstParse = GuidanceValidator.parseAndValidate(firstResult, confirmedSigns)

        // Check if we got real clinical data (not the fallback placeholder)
        if (firstParse.wasValid && firstParse.guidance.possibleCondition != "Refer: unable to parse AI assessment") {
            Log.d(TAG, "First attempt succeeded, triage: ${firstParse.guidance.triageLevel}")
            return firstParse.guidance
        }

        // First attempt produced unparseable output — retry once
        Log.w(TAG, "First attempt unparseable (warnings: ${firstParse.validationWarnings}), retrying with cleared cache")
        setStatus("Retrying...")

        if (modelHandle != 0L) {
            LlamaCpp.clearContext(modelHandle)
        }

        val secondResult = runMedGemmaInference(state)
        val secondPrefix = secondResult.take(40).replace(Regex("[a-zA-Z]{4,}"), "***")
        Log.d(TAG, "Attempt 2: ${secondResult.length} chars, starts with: $secondPrefix")
        val secondParse = GuidanceValidator.parseAndValidate(secondResult, confirmedSigns)

        if (!secondParse.wasValid || secondParse.guidance.possibleCondition == "Refer: unable to parse AI assessment") {
            Log.e(TAG, "Both attempts failed (warnings: ${secondParse.validationWarnings})")
            return null
        }

        Log.d(TAG, "Second attempt succeeded, triage: ${secondParse.guidance.triageLevel}")
        return secondParse.guidance
    }

    private fun setStatus(status: String) {
        _uiState.update { it.copy(processingStatus = status) }
    }

    private fun runMedGemmaInference(state: HealthDemoUiState): String {
        if (!LlamaCpp.isAvailable()) {
            throw IllegalStateException("llama.cpp native library not available")
        }

        // Load model if not already loaded
        if (modelHandle == 0L) {
            setStatus("Loading...")
            val modelPath = AppSettings.getLlmModelPath(appContext)
                ?: ModelAssetManager.getModelPath(appContext, ModelAssetManager.LLM_MODEL)
            Log.d(TAG, "Device: ${DeviceInfo.summary(appContext)}")
            Log.d(TAG, "Native variant: ${LlamaCpp.getLoadedVariant()}, perf cores: ${LlamaCpp.getPerfCoreInfo()}")
            Log.d(TAG, "Loading model from $modelPath")

            val file = java.io.File(modelPath)
            if (!file.exists()) {
                throw IllegalStateException("Model file not found at $modelPath. Please select a model in Settings.")
            }

            // Tune n_batch to device RAM to avoid OOM on budget phones
            val nBatch = DeviceInfo.recommendedNBatch(appContext)
            modelHandle = LlamaCpp.initModel(
                modelPath = modelPath,
                nCtx = N_CTX,
                nGpuLayers = N_GPU_LAYERS,
                nBatch = nBatch
            )

            if (modelHandle == 0L) {
                throw IllegalStateException("Failed to load model")
            }
            Log.d(TAG, "MedGemma model loaded: handle=$modelHandle, threads=${LlamaCpp.getThreadCount(modelHandle)}, nBatch=$nBatch")
        }

        // Collect streamed response
        val responseBuilder = StringBuilder()
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                responseBuilder.append(token)
            }
        }

        val hasImage = state.capturedImageBytes != null

        if (hasImage) {
            // Load vision encoder if needed
            if (!visionLoaded) {
                setStatus("Loading vision...")
                val mmprojPath = ModelAssetManager.getModelPath(appContext, ModelAssetManager.VISION_MODEL)
                Log.d(TAG, "Loading vision encoder from $mmprojPath")
                val mmprojFile = java.io.File(mmprojPath)
                if (mmprojFile.exists()) {
                    visionLoaded = LlamaCpp.initVision(modelHandle, mmprojPath)
                    Log.d(TAG, "Vision encoder loaded: $visionLoaded")
                } else {
                    Log.w(TAG, "mmproj not found at $mmprojPath")
                }
            }

            if (visionLoaded) {
                // Preprocess image
                setStatus("Processing image...")
                val processedImage = ImagePreprocessor.preprocess(state.capturedImageBytes!!)
                    ?: state.capturedImageBytes
                Log.d(TAG, "Image preprocessed: ${state.capturedImageBytes.size} -> ${processedImage.size} bytes")

                setStatus("Analyzing image and symptoms...")
                val visionPrompt = ClinicalPrompt.buildVisionPrompt(
                    symptoms = state.symptoms,
                    age = state.age?.label ?: "Unknown",
                    sex = state.sex?.label ?: "Unknown",
                    vitals = formatVitals(state.vitalSigns),
                )
                Log.d(TAG, "Running vision inference (${processedImage.size} bytes image)")
                LlamaCpp.completionWithImage(
                    handle = modelHandle,
                    prompt = visionPrompt,
                    imageData = processedImage,
                    nPredict = 1024,
                    temperature = 0.5f,
                    topK = 40,
                    topP = 0.9f,
                    callback = callback,
                )
            } else {
                setStatus("Generating assessment...")
                val prompt = buildClinicalPrompt(state)
                Log.d(TAG, "Running text-only inference (vision unavailable)")
                LlamaCpp.completion(
                    handle = modelHandle, prompt = prompt, nPredict = 1024,
                    temperature = 0.5f, topK = 40, topP = 0.9f,
                    stopSequences = "", callback = callback,
                )
            }
        } else {
            // Text-only inference
            setStatus("Generating assessment...")
            val prompt = buildClinicalPrompt(state)
            Log.d(TAG, "Running text inference (${prompt.length} chars prompt)")
            LlamaCpp.completion(
                handle = modelHandle, prompt = prompt, nPredict = 1024,
                temperature = 0.5f, topK = 40, topP = 0.9f,
                stopSequences = "", callback = callback,
            )
        }

        val response = responseBuilder.toString().trim()
        Log.d(TAG, "Inference complete: ${response.length} chars")
        return response
    }

    private fun formatVitals(vitalSigns: VitalSigns): String {
        return buildString {
            if (vitalSigns.temperature.isNotBlank()) append("Temp: ${vitalSigns.temperature}°C, ")
            if (vitalSigns.heartRate.isNotBlank()) append("HR: ${vitalSigns.heartRate} bpm, ")
            if (vitalSigns.respiratoryRate.isNotBlank()) append("RR: ${vitalSigns.respiratoryRate}/min, ")
            if (vitalSigns.spO2.isNotBlank()) append("SpO2: ${vitalSigns.spO2}%, ")
            if (vitalSigns.bloodLoss.isNotBlank()) append("Blood loss: ${vitalSigns.bloodLoss}")
        }.trimEnd(',', ' ').ifEmpty { "Not recorded" }
    }

    private fun buildClinicalPrompt(state: HealthDemoUiState): String {
        return ClinicalPrompt.buildTextPrompt(
            symptoms = state.symptoms,
            age = state.age?.label ?: "Unknown",
            sex = state.sex?.label ?: "Unknown",
            vitals = formatVitals(state.vitalSigns),
        )
    }

    fun buildSavedAssessment(): SavedAssessment {
        val state = _uiState.value
        return SavedAssessment(
            sessionStartTime = if (state.sessionStartTime != 0L) state.sessionStartTime else System.currentTimeMillis(),
            role = state.role ?: PatientRole.Other,
            customRole = state.customRole,
            symptoms = state.symptoms,
            durationValue = state.durationValue,
            durationUnit = state.durationUnit,
            ageYears = state.ageYears,
            ageMonths = state.ageMonths,
            age = state.age,
            sex = state.sex,
            vitalSigns = state.vitalSigns,
            confirmedSigns = state.confirmedSigns,
            traditionalMedicine = state.traditionalMedicine,
            treatmentAdministered = state.treatmentAdministered,
            guidance = state.guidance!!,
            latitude = state.capturedLocation?.latitude,
            longitude = state.capturedLocation?.longitude,
            locationAccuracyMeters = state.capturedLocation?.accuracyMeters
        )
    }

    fun markSaved(assessment: SavedAssessment) {
        _uiState.update { it.copy(savedAssessment = assessment) }
        HealthDemoAnalytics.logAssessmentSaved()
    }

    // ─── Danger sign flow ────────────────────────────────────────────────────────

    fun confirmDangerSign(sign: String) {
        _uiState.update { it.copy(
            confirmedSigns = it.confirmedSigns + sign,
            dangerSignIndex = it.dangerSignIndex + 1
        )}
    }

    fun dismissDangerSign() {
        _uiState.update { it.copy(dangerSignIndex = it.dangerSignIndex + 1) }
    }

    // ─── Clinician confirmation / referral ───────────────────────────────────────

    fun setClinicianConfirmation(confirmation: ClinicianConfirmation) {
        _uiState.update { it.copy(clinicianConfirmation = confirmation) }
    }

    fun setReferralInfo(referral: ReferralInfo) {
        _uiState.update { it.copy(referralInfo = referral) }
    }

    // ─── Pause / Resume ──────────────────────────────────────────────────────────

    fun buildPausedConsultation(reason: PauseReason?, note: String): PausedConsultation {
        val state = _uiState.value
        return PausedConsultation(
            role = state.role ?: PatientRole.Other,
            customRole = state.customRole,
            symptoms = state.symptoms,
            durationValue = state.durationValue,
            durationUnit = state.durationUnit,
            ageYears = state.ageYears,
            ageMonths = state.ageMonths,
            age = state.age,
            sex = state.sex,
            vitalSigns = state.vitalSigns,
            checkedSigns = state.checkedSigns,
            confirmedSigns = state.confirmedSigns,
            pauseReason = reason,
            note = note
        )
    }

    fun loadPausedConsultation(paused: PausedConsultation) {
        _uiState.update { it.copy(
            role = paused.role,
            customRole = paused.customRole,
            symptoms = paused.symptoms,
            durationValue = paused.durationValue,
            durationUnit = paused.durationUnit,
            ageYears = paused.ageYears,
            ageMonths = paused.ageMonths,
            age = paused.age,
            sex = paused.sex,
            vitalSigns = paused.vitalSigns,
            checkedSigns = paused.checkedSigns,
            confirmedSigns = paused.confirmedSigns,
            pendingSignAlert = null,
            guidance = null,
            savedAssessment = null,
            inferenceError = null,
            dangerSignIndex = 0,
            clinicianConfirmation = null,
            referralInfo = null,
        )}
    }

    fun resetAssessment() {
        // Preserve the role selection across assessments
        val currentRole = _uiState.value.role
        val currentCustomRole = _uiState.value.customRole
        _uiState.value = HealthDemoUiState(role = currentRole, customRole = currentCustomRole)
    }

    /** Cancel a running inference. The health worker can tap this if generation takes too long. */
    fun cancelInference() {
        if (modelHandle != 0L) {
            LlamaCpp.stopCompletion(modelHandle)
            Log.d(TAG, "Inference cancelled by user")
        }
        _uiState.update { it.copy(isProcessing = false, processingStatus = "") }
    }

    fun clearGuidance() {
        _uiState.update { it.copy(guidance = null, savedAssessment = null, inferenceError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        if (modelHandle != 0L) {
            LlamaCpp.releaseModel(modelHandle)
            modelHandle = 0L
            Log.d(TAG, "MedGemma model released")
        }
    }
}
