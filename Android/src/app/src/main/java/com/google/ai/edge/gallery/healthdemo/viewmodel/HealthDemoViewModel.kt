package com.google.ai.edge.gallery.healthdemo.viewmodel

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.healthdemo.data.AgeRange
import com.google.ai.edge.gallery.healthdemo.data.HealthGuidance
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import com.google.ai.edge.gallery.healthdemo.data.Sex
import com.google.ai.edge.gallery.healthdemo.data.SymptomGraphDatabase
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class HealthDemoUiState(
    // Role selection
    val role: PatientRole? = null,
    val customRole: String = "",

    // Patient assessment form
    val symptoms: String = "",
    val age: AgeRange? = null,
    val sex: Sex? = null,
    val vitalSigns: VitalSigns = VitalSigns(),

    // Guidance result
    val guidance: HealthGuidance? = null,

    // Saved assessment (set after save)
    val savedAssessment: SavedAssessment? = null,

    // Processing flag (kept for future async support)
    val isProcessing: Boolean = false
)

@HiltViewModel
class HealthDemoViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HealthDemoUiState())
    val uiState = _uiState.asStateFlow()

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

    fun setVitalSigns(vitalSigns: VitalSigns) {
        _uiState.update { it.copy(vitalSigns = vitalSigns) }
    }

    /**
     * Query the local symptom graph database and store the guidance result.
     */
    fun getGuidance() {
        val state = _uiState.value
        val node = SymptomGraphDatabase.query(
            symptoms = state.symptoms,
            age = state.age,
            sex = state.sex
        )
        val guidance = HealthGuidance(
            possibleCondition = node.possibleCondition,
            suggestedTreatment = node.suggestedTreatment,
            recommendedNextSteps = node.recommendedNextSteps
        )
        _uiState.update { it.copy(guidance = guidance, savedAssessment = null) }
    }

    /**
     * Build and return a SavedAssessment from the current state.
     * The caller should persist it via the repository.
     */
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

    /**
     * Mark the current assessment as saved (shows the confirmation banner).
     */
    fun markSaved(assessment: SavedAssessment) {
        _uiState.update { it.copy(savedAssessment = assessment) }
    }

    /**
     * Reset everything for a brand-new assessment.
     */
    fun resetAssessment() {
        _uiState.value = HealthDemoUiState()
    }

    /**
     * Reset only the guidance (keep role/patient details for a re-query).
     */
    fun clearGuidance() {
        _uiState.update { it.copy(guidance = null, savedAssessment = null) }
    }
}
