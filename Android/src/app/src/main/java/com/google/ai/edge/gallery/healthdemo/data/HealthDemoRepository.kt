package com.google.ai.edge.gallery.healthdemo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory repository for saved and paused patient assessments.
 */
class HealthDemoRepository {

    private val _savedAssessments = MutableStateFlow<List<SavedAssessment>>(emptyList())
    val savedAssessments: StateFlow<List<SavedAssessment>> = _savedAssessments.asStateFlow()

    private val _pausedConsultations = MutableStateFlow<List<PausedConsultation>>(emptyList())
    val pausedConsultations: StateFlow<List<PausedConsultation>> = _pausedConsultations.asStateFlow()

    fun save(assessment: SavedAssessment) {
        _savedAssessments.value = listOf(assessment) + _savedAssessments.value
    }

    fun remove(id: String) {
        _savedAssessments.value = _savedAssessments.value.filter { it.id != id }
    }

    fun updateConfirmation(id: String, confirmation: ClinicianConfirmation, referral: ReferralInfo?) {
        _savedAssessments.value = _savedAssessments.value.map { a ->
            if (a.id == id) a.copy(clinicianConfirmation = confirmation, referralInfo = referral) else a
        }
    }

    fun getById(id: String): SavedAssessment? =
        _savedAssessments.value.find { it.id == id }

    fun savePaused(consultation: PausedConsultation) {
        _pausedConsultations.value = listOf(consultation) + _pausedConsultations.value
    }

    fun removePaused(id: String) {
        _pausedConsultations.value = _pausedConsultations.value.filter { it.id != id }
    }

    fun getPausedById(id: String): PausedConsultation? =
        _pausedConsultations.value.find { it.id == id }

    fun updateReferral(id: String, referral: ReferralInfo) {
        _savedAssessments.value = _savedAssessments.value.map { a ->
            if (a.id == id) a.copy(referralInfo = referral) else a
        }
    }
}
