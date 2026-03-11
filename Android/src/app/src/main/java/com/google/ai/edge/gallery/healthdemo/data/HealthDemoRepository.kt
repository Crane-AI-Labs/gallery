package com.google.ai.edge.gallery.healthdemo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory repository for saved patient assessments.
 */
class HealthDemoRepository {

    private val _savedAssessments = MutableStateFlow<List<SavedAssessment>>(emptyList())
    val savedAssessments: StateFlow<List<SavedAssessment>> = _savedAssessments.asStateFlow()

    fun save(assessment: SavedAssessment) {
        _savedAssessments.value = listOf(assessment) + _savedAssessments.value
    }

    fun getById(id: String): SavedAssessment? =
        _savedAssessments.value.find { it.id == id }
}
