package com.google.ai.edge.gallery.analytics

import android.content.Context
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.firebaseAnalytics

/**
 * Tracks key health demo workflow events.
 * All events are batched by Firebase and only dispatched during periodic sync
 * or when Firebase's internal threshold is reached.
 */
object HealthDemoAnalytics {

    fun logRoleSelected(role: String) {
        firebaseAnalytics?.logEvent("health_role_selected", bundleOf(
            "role" to role,
        ))
    }

    fun logAssessmentStarted(context: Context) {
        BatteryAnalytics.logBatteryEvent(context, trigger = "assessment_start")
        firebaseAnalytics?.logEvent("health_assessment_started", null)
    }

    fun logInferenceCompleted(context: Context, durationMs: Long, hasImage: Boolean) {
        BatteryAnalytics.logBatteryEvent(context, trigger = "inference_complete")
        firebaseAnalytics?.logEvent("health_inference_completed", bundleOf(
            "duration_ms" to durationMs,
            "has_image" to hasImage.toString(),
        ))
    }

    fun logInferenceFailed(errorType: String) {
        firebaseAnalytics?.logEvent("health_inference_failed", bundleOf(
            "error_type" to errorType,
        ))
    }

    fun logAssessmentSaved() {
        firebaseAnalytics?.logEvent("health_assessment_saved", null)
    }

    fun logFeedbackSubmitted(rating: String) {
        firebaseAnalytics?.logEvent("health_feedback_submitted", bundleOf(
            "rating" to rating,
        ))
    }

    fun logVoiceNoteUsed() {
        firebaseAnalytics?.logEvent("health_voice_note_used", null)
    }

    fun logImageCaptured() {
        firebaseAnalytics?.logEvent("health_image_captured", null)
    }
}
