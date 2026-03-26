package com.google.ai.edge.gallery.healthdemo.viewmodel

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.healthdemo.data.AgeRange
import com.google.ai.edge.gallery.healthdemo.data.HealthGuidance
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import com.google.ai.edge.gallery.healthdemo.data.Sex
import com.google.ai.edge.gallery.healthdemo.data.ClinicalPrompt
import com.google.ai.edge.gallery.healthdemo.data.GuidanceValidator
import com.google.ai.edge.gallery.healthdemo.data.ImagePreprocessor
import com.google.ai.edge.gallery.healthdemo.data.MedAsrEngine
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.Context
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HealthDemoViewModel"
private const val DEFAULT_MODEL_PATH = "/data/local/tmp/medgemma-v5b-Q4_0.gguf"
private const val MMPROJ_PATH = "/data/local/tmp/medgemma-mmproj-Q8_0.gguf"
private const val N_CTX = 2048
private const val N_GPU_LAYERS = 99  // offload as many layers as possible to GPU

data class HealthDemoUiState(
    // Role selection
    val role: PatientRole? = null,
    val customRole: String = "",

    // Patient assessment form
    val symptoms: String = "",
    val age: AgeRange? = null,
    val sex: Sex? = null,
    val vitalSigns: VitalSigns = VitalSigns(),
    val capturedImageBytes: ByteArray? = null,

    // Guidance result
    val guidance: HealthGuidance? = null,

    // Saved assessment (set after save)
    val savedAssessment: SavedAssessment? = null,

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
    }

    fun setCustomRole(text: String) {
        _uiState.update { it.copy(customRole = text) }
    }

    fun setSymptoms(symptoms: String) {
        _uiState.update { it.copy(symptoms = symptoms) }
    }

    fun setAge(age: AgeRange) {
        _uiState.update { it.copy(age = age) }
    }

    fun setSex(sex: Sex) {
        _uiState.update { it.copy(sex = sex) }
    }

    fun setCapturedImage(imageBytes: ByteArray?) {
        _uiState.update { it.copy(capturedImageBytes = imageBytes) }
    }

    private var recordedBytes = java.io.ByteArrayOutputStream()

    fun startVoiceRecording() {
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
                MedAsrEngine.setModelPath(AppSettings.getAsrModelPath(appContext))
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
                    Log.d(TAG, "Transcription result: $transcript")
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

    /**
     * Run MedGemma inference to generate clinical guidance.
     * No fallback — if inference fails, show the error to the user.
     */
    fun getGuidance() {
        val state = _uiState.value
        _uiState.update { it.copy(isProcessing = true, inferenceError = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val guidance = runMedGemmaInference(state)
                _uiState.update { it.copy(guidance = guidance, savedAssessment = null, isProcessing = false) }
            } catch (e: Exception) {
                Log.e(TAG, "MedGemma inference failed", e)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        inferenceError = "Inference failed: ${e.message ?: "Unknown error"}. Please try again."
                    )
                }
            }
        }
    }

    private fun setStatus(status: String) {
        _uiState.update { it.copy(processingStatus = status) }
    }

    private fun runMedGemmaInference(state: HealthDemoUiState): HealthGuidance {
        if (!LlamaCpp.isAvailable()) {
            throw IllegalStateException("llama.cpp native library not available")
        }

        // Load model if not already loaded
        if (modelHandle == 0L) {
            setStatus("Loading AI model...")
            val modelPath = AppSettings.getLlmModelPath(appContext) ?: DEFAULT_MODEL_PATH
            Log.d(TAG, "Loading model from $modelPath")

            val file = java.io.File(modelPath)
            if (!file.exists()) {
                throw IllegalStateException("Model file not found at $modelPath. Please select a model in Settings.")
            }

            modelHandle = LlamaCpp.initModel(modelPath, N_CTX, N_GPU_LAYERS)

            if (modelHandle == 0L) {
                throw IllegalStateException("Failed to load model")
            }
            Log.d(TAG, "MedGemma model loaded: handle=$modelHandle")
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
                setStatus("Loading vision encoder...")
                Log.d(TAG, "Loading vision encoder from $MMPROJ_PATH")
                val mmprojFile = java.io.File(MMPROJ_PATH)
                if (mmprojFile.exists()) {
                    visionLoaded = LlamaCpp.initVision(modelHandle, MMPROJ_PATH)
                    Log.d(TAG, "Vision encoder loaded: $visionLoaded")
                } else {
                    Log.w(TAG, "mmproj not found at $MMPROJ_PATH")
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
            Log.d(TAG, "Running text inference (${prompt.length} chars)")
            LlamaCpp.completion(
                handle = modelHandle, prompt = prompt, nPredict = 1024,
                temperature = 0.5f, topK = 40, topP = 0.9f,
                stopSequences = "", callback = callback,
            )
        }

        val response = responseBuilder.toString().trim()
        Log.d(TAG, "Inference complete. Response length: ${response.length}")
        Log.d(TAG, "Response: $response")

        val result = GuidanceValidator.parseAndValidate(response)
        if (!result.wasValid) {
            Log.w(TAG, "Validation warnings: ${result.validationWarnings}")
        }
        return result.guidance
    }

    private fun formatVitals(vitalSigns: VitalSigns): String {
        return buildString {
            if (vitalSigns.temperature.isNotBlank()) append("Temperature: ${vitalSigns.temperature}°C, ")
            if (vitalSigns.pulseRate.isNotBlank()) append("Pulse: ${vitalSigns.pulseRate} bpm, ")
            if (vitalSigns.bloodPressure.isNotBlank()) append("BP: ${vitalSigns.bloodPressure}, ")
            if (vitalSigns.respiratoryRate.isNotBlank()) append("RR: ${vitalSigns.respiratoryRate}/min")
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
            role = state.role ?: PatientRole.Other,
            customRole = state.customRole,
            symptoms = state.symptoms,
            age = state.age,
            sex = state.sex,
            vitalSigns = state.vitalSigns,
            guidance = state.guidance!!
        )
    }

    fun markSaved(assessment: SavedAssessment) {
        _uiState.update { it.copy(savedAssessment = assessment) }
    }

    fun resetAssessment() {
        // Preserve the role selection across assessments
        val currentRole = _uiState.value.role
        val currentCustomRole = _uiState.value.customRole
        _uiState.value = HealthDemoUiState(role = currentRole, customRole = currentCustomRole)
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
