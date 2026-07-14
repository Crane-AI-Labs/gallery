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
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.healthdemo.data.ClinicalPrompt
import com.google.ai.edge.gallery.healthdemo.data.GandaModelDownloader
import com.google.ai.edge.gallery.healthdemo.data.GandaTranslator
import com.google.ai.edge.gallery.healthdemo.data.GuidanceValidator
import com.google.ai.edge.gallery.healthdemo.data.ImagePreprocessor
import com.google.ai.edge.gallery.healthdemo.data.MedAsrEngine
import com.google.ai.edge.gallery.healthdemo.data.MmsLugandaAsrEngine
import com.google.ai.edge.gallery.healthdemo.data.UgandaApiSync
import com.google.ai.edge.gallery.healthdemo.data.TraditionalMedicine
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import com.google.ai.edge.gallery.llm.DeviceInfo
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
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
// 1.0.5 dropped this 2048→1024 to halve KV cache footprint on the assumption
// of a ~670-token prompt; 1.0.7 restored the Emergency + Home examples and
// pushed prompt size to ~1091 tokens, so 1024 + nPredict=384 = 1475 was
// triggering native cache eviction every decode (`exceeds n_ctx, clearing
// cache`). 1.0.9 reverts to 2048 — A26-class hardware has enough RAM to
// absorb the bigger KV cache, and avoiding the slide-window mid-generate
// is worth more than the prefill memory savings.
private const val N_CTX = 2048
private const val N_GPU_LAYERS = 99  // offload as many layers as possible to GPU

// Hard watchdog for the full inference pipeline (load + first attempt +
// optional retry). If generation runs past this point the native call is
// aborted via LlamaCpp.stopCompletion so the UI doesn't sit on the Generating
// screen indefinitely — field testers at Makerere reported having to kill
// the app after a minute of spinner on danger-sign cases.
//
// Trajectory:
//   60s  (initial) — false-positive on Galaxy A26-class hardware
//   120s (1.0.5)   — covered Pixel 8 / S24 comfortably
//   240s (1.0.9)   — Galaxy A26 with restored 3-example prompt + ~150
//                    output tokens runs ~150-180s (dotprod-only, no i8mm).
//                    4 minutes is the published deadline a clinician will
//                    tolerate while looking at a spinner; beyond that the
//                    UX implication is 'this device cannot run inference,
//                    surface the error and let them retry / refer manually'.
private const val INFERENCE_TIMEOUT_MS = 240_000L

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
    // Makerere v2 #4: free-text detail shown when traditionalMedicine == Yes.
    val traditionalMedicineDetails: String = "",

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

    // Wall-clock ms for the most recent inference. Captured in
    // getGuidance() once inference returns, copied onto the saved
    // assessment in buildSavedAssessment(). Null until the first run.
    val lastInferenceMs: Long? = null,
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

    // Tier-0 safety substrate: ONE shared single-thread executor that every
    // MedGemma native op (prewarm, inference, retry) runs on. llama_decode is
    // not thread-safe on a single context, so this guarantees the background
    // prewarm and a Generate tap can never drive the context concurrently —
    // a Generate that lands mid-prewarm simply queues behind it and inherits
    // the warm prefix. Do NOT launch any modelHandle work off a different
    // dispatcher.
    private val medGemmaExecutor = Dispatchers.IO.limitedParallelism(1)
    // true once the constant instruction+few-shot prefix has been prefilled
    // into the KV cache (by prewarm or a prior assessment).
    @Volatile private var prefixWarmed = false

    fun setRole(role: PatientRole) {
        val prevCustomRole = _uiState.value.customRole
        val prevRole = _uiState.value.role
        // Makerere v2 #1: if we're being asked to set role=Other *and* a
        // custom label is already in-flight (nav-graph cold-start restore or
        // a user still holding Other from a prior session), don't clobber
        // customRole back to "". That used to wipe "Community health
        // volunteer" the moment nav-graph restore ran setRole(Other) before
        // setCustomRole(label), producing a blank pill on the symptoms screen.
        val keepCustom = role == PatientRole.Other && (prevRole == PatientRole.Other || prevCustomRole.isNotBlank())
        _uiState.update { it.copy(role = role, customRole = if (keepCustom) prevCustomRole else "") }
        // Persist Other only when there's no custom label to carry — otherwise
        // the literal "Other" would momentarily win in AppSettings between
        // this call and setCustomRole(), and a process death in that window
        // would lose the custom label on next boot.
        if (!(role == PatientRole.Other && keepCustom)) {
            AppSettings.saveRole(appContext, role.label)
        }
        HealthDemoAnalytics.logRoleSelected(role.name)
    }

    fun setCustomRole(text: String) {
        _uiState.update { it.copy(customRole = text) }
        // Keep the persisted role label in sync when the user types a custom
        // role, so "Other → Community health volunteer" doesn't get lost
        // when resetAssessment() re-reads AppSettings.
        if (text.isNotBlank()) AppSettings.saveRole(appContext, text)
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
        // If switching away from Yes, clear the details string so stale input
        // doesn't get persisted on a subsequent No.
        val clearDetails = tm != TraditionalMedicine.Yes
        _uiState.update {
            it.copy(
                traditionalMedicine = tm,
                traditionalMedicineDetails = if (clearDetails) "" else it.traditionalMedicineDetails
            )
        }
    }

    fun setTraditionalMedicineDetails(text: String) {
        _uiState.update { it.copy(traditionalMedicineDetails = text) }
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
        if (imageBytes != null) {
            HealthDemoAnalytics.logImageCaptured()
            // Kick off the ~184s SigLIP encode in the background NOW, while the
            // worker keeps filling the form, so Generate only prefills the tail.
            prewarmImage(imageBytes)
        }
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
                val transcript = if (BuildConfig.FLAVOR == "ganda") {
                    transcribeGanda(pcmBytes)
                } else {
                    MedAsrEngine.setModelPath(
                        AppSettings.getAsrModelPath(appContext)
                            ?: ModelAssetManager.getModelPath(appContext, ModelAssetManager.ASR_MODEL)
                    )
                    MedAsrEngine.setTokenizerPath(
                        AppSettings.getTokenizerPath(appContext)
                            ?: ModelAssetManager.getModelPath(appContext, ModelAssetManager.ASR_TOKENIZER)
                    )
                    MedAsrEngine.transcribe(pcmBytes)
                }
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

    /**
     * ganda flavor: Luganda speech → MMS Luganda ASR → Ganda Gemma LUG→EN
     * draft translation. The English draft goes into the EDITABLE symptoms
     * field for the health worker to confirm/correct — Ganda Gemma's LUG→EN
     * is draft quality (it's trained EN→LUG), so a human check is mandatory
     * before the text feeds MedGemma. Falls back to the raw Luganda
     * transcript if translation fails, which the worker can rewrite.
     */
    private fun transcribeGanda(pcmBytes: ByteArray): String {
        if (!GandaModelDownloader.modelsReady(appContext)) {
            // Kick a (resumable) download attempt and tell the worker exactly
            // which file is missing and why voice isn't available. Don't
            // blame WiFi unless the phone is actually offline — a 404 from
            // the model server looks identical to the worker otherwise.
            viewModelScope.launch(Dispatchers.IO) {
                try { GandaModelDownloader.ensureModels(appContext) } catch (_: Exception) { /* logged inside */ }
            }
            val missing = GandaModelDownloader.missingModels(appContext).joinToString(", ")
            throw IllegalStateException(
                when {
                    GandaModelDownloader.isDownloading ->
                        "Luganda voice files are still downloading ($missing) — try again in a few minutes."
                    !UgandaApiSync.isOnline(appContext) ->
                        "Missing voice files ($missing) — connect to WiFi to download them."
                    else ->
                        "The voice file $missing is not available from the server yet. " +
                            "Typing symptoms in English still works."
                }
            )
        }
        MmsLugandaAsrEngine.setModelPath(
            ModelAssetManager.getModelPath(appContext, ModelAssetManager.MMS_ASR_MODEL)
        )
        MmsLugandaAsrEngine.setVocabPath(
            ModelAssetManager.getModelPath(appContext, ModelAssetManager.MMS_ASR_VOCAB)
        )
        val luganda = MmsLugandaAsrEngine.transcribe(pcmBytes)
        if (luganda.isBlank()) return ""
        Log.d(TAG, "Luganda transcript: ${luganda.length} chars")

        // Free the ~1 GB ASR session before loading the 0.9 GB translator so
        // both never sit in RAM together on 4 GB-class devices.
        MmsLugandaAsrEngine.release()

        val english = GandaTranslator.translate(
            modelPath = ModelAssetManager.getModelPath(appContext, ModelAssetManager.GANDA_LLM),
            lugandaText = luganda,
        )
        return if (english != null) {
            Log.d(TAG, "LUG→EN draft ready (${english.length} chars)")
            english
        } else {
            Log.w(TAG, "Translation unavailable — inserting raw Luganda transcript")
            luganda
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

        // Pre-flight RAM check — but ONLY on cold model load. Once the model
        // is resident (modelHandle != 0L) we've already proven the device
        // can hold it; the loaded weights themselves consume ~2.7 GB so
        // AvailMem will look "low" indefinitely thereafter and the gate
        // would block every second assessment in the same session. The
        // reason this gate exists is the silent OOM-kill on Galaxy A06
        // (~3.7 GB total, ~1.7 GB free) during cold model load — which
        // only happens before modelHandle is set. Threshold 2200 MB
        // picked from field data: A06 cold-loads consistently OOM, A26
        // (~3 GB avail at boot) succeeds. Bug fix in 1.0.11: was firing
        // on warm runs too and blocking KV-cache reuse across assessments.
        if (modelHandle == 0L) {
            val availableMb = DeviceInfo.availableRamMb(appContext)
            // 1.0.13: lowered 2200→1500. Galaxy A06 (Samsung One UI 7 stock
            // overhead: launcher, Bixby, Galaxy Store, Samsung Push) sits at
            // ~1.7 GB available with NOTHING else open, so any threshold
            // ≥1800 was making A06 unreachable from cold boot. 1500 is the
            // empirical floor: model is 2.0 GB Q3_K_M but mmap'd, so peak
            // resident is bounded by available page cache, not the file
            // size. 1500 MB AvailMem corresponds to ~1.2 GB actually
            // usable after kernel+lmkd reservation — tight but the only
            // honest threshold for this device class. Field deployments
            // should still see the error message if the user is running
            // browsers + Maps + WhatsApp simultaneously.
            if (availableMb < 1500) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        inferenceError = "Only ${availableMb} MB of RAM is free, but the AI model needs at least 1.5 GB. " +
                            "Close other apps and try again."
                    )
                }
                HealthDemoAnalytics.logInferenceFailed("preflight_low_ram_${availableMb}mb")
                return
            }
        }

        val state = _uiState.value
        _uiState.update { it.copy(isProcessing = true, inferenceError = null) }

        HealthDemoAnalytics.logAssessmentStarted(appContext)

        // Route through the shared serialized executor so a Generate that
        // lands mid-prewarm queues behind it (and inherits the warm prefix)
        // rather than double-driving the llama context.
        viewModelScope.launch(medGemmaExecutor) {
            val startMs = System.currentTimeMillis()
            try {
                // Makerere #4: cap total inference at INFERENCE_TIMEOUT_MS.
                // If we time out, nudge the native side to unblock and
                // surface a clear error instead of a silent indefinite spin.
                val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    runMedGemmaInferenceWithRetry(state)
                }
                val durationMs = System.currentTimeMillis() - startMs
                if (result != null) {
                    HealthDemoAnalytics.logInferenceCompleted(
                        appContext, durationMs, hasImage = state.capturedImageBytes != null
                    )
                    _uiState.update { it.copy(
                        guidance = result,
                        savedAssessment = null,
                        isProcessing = false,
                        lastInferenceMs = durationMs,
                    ) }
                } else if (durationMs >= INFERENCE_TIMEOUT_MS) {
                    // Watchdog fired — abort the native call so the next
                    // attempt isn't starved waiting on the same thread.
                    if (modelHandle != 0L) {
                        try { LlamaCpp.stopCompletion(modelHandle) } catch (_: Exception) { /* best effort */ }
                    }
                    HealthDemoAnalytics.logInferenceFailed("watchdog_timeout")
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            inferenceError = "Assessment took too long and was stopped. Please try again."
                        )
                    }
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
            prefixWarmed = false  // clear wiped the cached prefix; retry re-fills it
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

    /**
     * Load MedGemma into [modelHandle] if not already resident. Idempotent.
     * MUST be called on [medGemmaExecutor] (shared by inference + prewarm).
     * @return true if a handle is available.
     */
    private fun ensureModelLoaded(): Boolean {
        if (!LlamaCpp.isAvailable()) {
            throw IllegalStateException("llama.cpp native library not available")
        }
        if (modelHandle != 0L) return true

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
        // 1.0.16 perf: f16 KV cache on devices with headroom. f16 avoids
        // the per-token dequantisation that Q4_0 KV costs during attention
        // on CPU — measured ~8% faster decode on the A17. It roughly
        // doubles the KV footprint (~+300-400 MB at n_ctx=2048), so only
        // enable it above 6 GB total RAM; low-RAM handsets keep Q4_0.
        //   0 = f16, 2 = Q4_0
        val kvType = if (DeviceInfo.totalRamMb(appContext) >= 6000) 0 else 2
        modelHandle = LlamaCpp.initModel(
            modelPath = modelPath,
            nCtx = N_CTX,
            nGpuLayers = N_GPU_LAYERS,
            kvCacheType = kvType,
            nBatch = nBatch
        )
        prefixWarmed = false
        if (modelHandle == 0L) {
            throw IllegalStateException("Failed to load model")
        }
        Log.d(TAG, "MedGemma model loaded: handle=$modelHandle, threads=${LlamaCpp.getThreadCount(modelHandle)}, nBatch=$nBatch")
        return true
    }

    /**
     * Background prewarm: load the model and prefill the constant
     * instruction+few-shot prefix into the KV cache while the health worker is
     * still entering symptoms, so the FIRST assessment skips the ~cold prefill.
     * Runs on [medGemmaExecutor], so it can never race an actual assessment.
     * Idempotent, RAM-gated, and cheap to call repeatedly (e.g. on screen
     * entry). It does NOT set isProcessing — invisible to the UI.
     */
    fun prewarm() {
        if (prefixWarmed || _uiState.value.isProcessing) return
        if (BuildConfig.FLAVOR == "ganda") return  // ganda's first model is ASR/translator, not MedGemma
        // If a photo is attached we want the VISION prefix resident, not the
        // text prefix — prewarmImage() owns the KV in that case.
        if (_uiState.value.capturedImageBytes != null) return
        viewModelScope.launch(medGemmaExecutor) {
            try {
                // Only prewarm on devices that can actually cold-load (same
                // floor as the inference pre-flight gate).
                if (modelHandle == 0L && DeviceInfo.availableRamMb(appContext) < 1500) return@launch
                if (!ensureModelLoaded()) return@launch
                if (prefixWarmed) return@launch
                // Prefill the constant prefix with EMPTY patient fields — that
                // maximises the reusable common prefix for any real patient.
                val prefixPrompt = ClinicalPrompt.buildTextPrompt(
                    symptoms = "", age = "", sex = "", vitals = "")
                val t0 = System.currentTimeMillis()
                val n = LlamaCpp.prefill(modelHandle, prefixPrompt)
                prefixWarmed = n > 0
                Log.d(TAG, "Prewarm: prefilled $n prefix tokens in ${System.currentTimeMillis() - t0}ms (warmed=$prefixWarmed)")
            } catch (e: Exception) {
                Log.w(TAG, "Prewarm skipped: ${e.message}")
            }
        }
    }

    private var loadedVisionSize: Int = -1

    /**
     * Longest-edge cap for the preprocessed image. In fast-image mode we cap at
     * the reduced ViT size so the encoder sees a single tile (no pan-and-scan);
     * otherwise the default 768 (upscaled to the model's native 896).
     */
    private fun visionInputMaxDim(): Int =
        if (AppSettings.isFastImageMode(appContext)) AppSettings.FAST_IMAGE_SIZE else 768

    /**
     * Idempotently load the vision encoder (mmproj) at the resolution implied by
     * the "fast image mode" setting. Re-inits (and clears any stale resident
     * prefix) if the setting changed since the last load. MUST run on
     * [medGemmaExecutor].
     */
    private fun ensureVisionLoaded(): Boolean {
        val wantSize = AppSettings.visionImageSize(appContext)
        if (visionLoaded && loadedVisionSize == wantSize) return true
        val mmprojPath = ModelAssetManager.getModelPath(appContext, ModelAssetManager.VISION_MODEL)
        if (!java.io.File(mmprojPath).exists()) {
            Log.w(TAG, "mmproj not found at $mmprojPath")
            return false
        }
        // Resolution change → the resident [prefix+image] KV is at the old size.
        if (visionLoaded && loadedVisionSize != wantSize) LlamaCpp.clearContext(modelHandle)
        visionLoaded = LlamaCpp.initVision(modelHandle, mmprojPath, wantSize)
        if (visionLoaded) loadedVisionSize = wantSize
        Log.d(TAG, "Vision encoder loaded: $visionLoaded (imageSize=$wantSize)")
        return visionLoaded
    }

    /**
     * Background eager image-encode (vision overlap). The ~184s SigLIP forward
     * depends only on the photo, so we run it the moment the image is attached —
     * concurrently with the worker's remaining data entry — and leave the
     * [constant prefix + image] KV resident. The subsequent Generate then only
     * prefills the patient-text tail (native fast path in nativeCompletionWithImage),
     * so the first vision assessment feels ~as fast as text. Bit-identical output.
     * Runs on [medGemmaExecutor] so it can never race an assessment.
     */
    fun prewarmImage(imageBytes: ByteArray) {
        if (_uiState.value.isProcessing) return
        if (BuildConfig.FLAVOR == "ganda") return
        viewModelScope.launch(medGemmaExecutor) {
            try {
                if (modelHandle == 0L && DeviceInfo.availableRamMb(appContext) < 1500) return@launch
                if (!ensureModelLoaded()) return@launch
                if (!ensureVisionLoaded()) return@launch
                // Preprocess to the exact bytes the real inference will feed, so
                // the native image-hash matches (deterministic JPEG re-encode).
                // If it ever doesn't match, the fast path simply falls back to a
                // full encode — never a wrong result.
                val processed = ImagePreprocessor.preprocess(imageBytes, visionInputMaxDim()) ?: imageBytes
                val prefix = ClinicalPrompt.buildVisionPrefix()
                val t0 = System.currentTimeMillis()
                val n = LlamaCpp.encodeImagePrefix(modelHandle, prefix, processed)
                prefixWarmed = false  // the vision encode overwrote any text prefix
                Log.d(TAG, "prewarmImage: encoded [prefix+image] -> $n tokens in ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                Log.w(TAG, "prewarmImage skipped: ${e.message}")
            }
        }
    }

    private fun runMedGemmaInference(state: HealthDemoUiState): String {
        // Load model if not already loaded (idempotent; prewarm may have done it)
        if (modelHandle == 0L) {
            setStatus("Loading...")
            ensureModelLoaded()
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
            // Load vision encoder if needed (prewarmImage usually did this already)
            if (!visionLoaded) {
                setStatus("Loading vision...")
                ensureVisionLoaded()
            }

            if (visionLoaded) {
                // Preprocess image (same maxDim as prewarmImage so the cached
                // eager-encode hash matches)
                setStatus("Processing image...")
                val processedImage = ImagePreprocessor.preprocess(state.capturedImageBytes!!, visionInputMaxDim())
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
                    // 384 fits prompt(~670) + decode within n_ctx=1024 with
                    // headroom; native code was previously sliding the cache
                    // because (656 + 1024) > 1024. Real outputs run ~150
                    // tokens, so 384 is plenty.
                    nPredict = 384,
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
                    handle = modelHandle, prompt = prompt, nPredict = 384,
                    temperature = 0.5f, topK = 40, topP = 0.9f,
                    // Stop on the closing root tag — the model emits one
                    // self-contained <r>…</r> envelope per assessment. Gemma
                    // usually emits EOG ~1 token after </r>, so the average
                    // saving is small (~1-2%); the real value is capping a
                    // runaway that keeps emitting past the tag toward nPredict.
                    stopSequences = "</r>", callback = callback,
                    // 1.0.16: ngram-map-k4v self-speculation. The XML output
                    // repeats prompt phrases heavily — ideal for ngram drafts.
                    specDecode = true,
                )
            }
        } else {
            // Text-only inference
            setStatus("Generating assessment...")
            val prompt = buildClinicalPrompt(state)
            Log.d(TAG, "Running text inference (${prompt.length} chars prompt)")
            LlamaCpp.completion(
                handle = modelHandle, prompt = prompt, nPredict = 384,
                temperature = 0.5f, topK = 40, topP = 0.9f,
                stopSequences = "</r>", callback = callback,
                // 1.0.16: ngram-map-k4v self-speculation (see above).
                specDecode = true,
            )
        }

        val response = responseBuilder.toString().trim()
        // A completed assessment leaves the constant prefix resident in KV, so
        // a later prewarm() call is redundant.
        prefixWarmed = true
        Log.d(TAG, "Inference complete: ${response.length} chars")
        return response
    }

    private fun formatVitals(vitalSigns: VitalSigns): String {
        return buildString {
            if (vitalSigns.temperature.isNotBlank()) append("Temp: ${vitalSigns.temperature}°C, ")
            if (vitalSigns.heartRate.isNotBlank()) append("HR: ${vitalSigns.heartRate} bpm, ")
            if (vitalSigns.respiratoryRate.isNotBlank()) append("RR: ${vitalSigns.respiratoryRate}/min, ")
            if (vitalSigns.spO2.isNotBlank()) append("SpO2: ${vitalSigns.spO2}%, ")
            if (vitalSigns.bloodPressure.isNotBlank()) append("BP: ${vitalSigns.bloodPressure} mmHg")
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
        // Makerere v2 #3: do NOT fall back to now() here. If the symptoms
        // screen's LaunchedEffect(Unit) didn't fire (e.g. VM recreated
        // mid-flow, deeplink entry), using now() makes the Completed time
        // ~= Started time — and the "< 1 min active" chip shows on every
        // row. Instead, mirror `timestamp` so they're exactly equal, letting
        // the detail sheet's `durationMs <= 0` branch hide the chip.
        val nowMs = System.currentTimeMillis()
        return SavedAssessment(
            timestamp = nowMs,
            sessionStartTime = if (state.sessionStartTime != 0L) state.sessionStartTime else nowMs,
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
            traditionalMedicineDetails = state.traditionalMedicineDetails,
            treatmentAdministered = state.treatmentAdministered,
            guidance = state.guidance!!,
            latitude = state.capturedLocation?.latitude,
            longitude = state.capturedLocation?.longitude,
            locationAccuracyMeters = state.capturedLocation?.accuracyMeters,
            inferenceMs = state.lastInferenceMs,
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
        // NOTE (1.0.16 perf): this intentionally does NOT clear the model KV
        // cache. The clinical prompt is a constant ~1000-token instruction +
        // few-shot prefix followed by the patient tail, so keeping the context
        // resident lets the native KV-reuse path skip re-processing that prefix
        // on every consecutive assessment (prefix caching — measured ~50% of
        // the per-assessment cost). Do NOT add clearContext/releaseModel here.
        // Preserve the role selection across assessments. Makerere #3:
        // prefer AppSettings (persisted) over the in-memory value, so a
        // process-death or navigation backstack pop that drops the VM state
        // still lands on the same role the clinician picked for this shift.
        val persistedLabel = AppSettings.getRole(appContext)
        val persistedRole = persistedLabel?.let { label ->
            PatientRole.entries.firstOrNull { it.label == label }
        }
        val persistedCustom = if (persistedRole == null && !persistedLabel.isNullOrBlank()) {
            persistedLabel
        } else ""

        val currentRole = _uiState.value.role ?: persistedRole
            ?: if (persistedCustom.isNotBlank()) PatientRole.Other else null
        val currentCustomRole = if (currentRole == PatientRole.Other) {
            _uiState.value.customRole.ifBlank { persistedCustom }
        } else ""

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
