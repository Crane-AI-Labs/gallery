package com.google.ai.edge.gallery.healthdemo.data

import android.util.Log
import org.json.JSONObject

private const val TAG = "GuidanceValidator"

/**
 * Validates and parses MedGemma output into safe, structured HealthGuidance.
 *
 * Guardrails:
 * 1. JSON schema compliance — must have required fields
 * 2. Triage category validation — must be one of the predefined categories
 * 3. Confidence threshold — auto-refer if "low" or parsing fails
 * 4. Fallback for malformed output — extract what we can, flag for review
 */
object GuidanceValidator {

    data class ParseResult(
        val guidance: HealthGuidance,
        val wasValid: Boolean,
        val validationWarnings: List<String> = emptyList(),
    )

    /**
     * Parse and validate a raw LLM response into HealthGuidance.
     * Always returns a result — never throws.
     */
    fun parseAndValidate(rawResponse: String): ParseResult {
        val warnings = mutableListOf<String>()

        // Try to extract JSON
        val jsonStr = extractJson(rawResponse)
        if (jsonStr == null) {
            Log.w(TAG, "No JSON found in response")
            return ParseResult(
                guidance = buildUnstructuredFallback(rawResponse),
                wasValid = false,
                validationWarnings = listOf("Could not extract JSON from model response"),
            )
        }

        val json: JSONObject
        try {
            json = JSONObject(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON: ${e.message}")
            return ParseResult(
                guidance = buildUnstructuredFallback(rawResponse),
                wasValid = false,
                validationWarnings = listOf("Invalid JSON in model response"),
            )
        }

        // Validate triage category
        val rawTriage = json.optString("triage", "")
        val triage = validateTriageCategory(rawTriage)
        if (triage != rawTriage) {
            warnings.add("Triage category '$rawTriage' was not recognized, defaulting to '$triage'")
        }

        // Validate confidence
        val rawConfidence = json.optString("confidence", "low")
        val confidence = validateConfidence(rawConfidence)
        if (confidence != rawConfidence) {
            warnings.add("Confidence '$rawConfidence' was not recognized, defaulting to '$confidence'")
        }

        // Auto-escalate: if confidence is low, bump triage to at least "Urgent clinic visit"
        val finalTriage = if (confidence == "low" && triage == "Home care") {
            warnings.add("Low confidence with 'Home care' triage auto-escalated to 'Urgent clinic visit'")
            "Urgent clinic visit"
        } else {
            triage
        }

        // Extract condition
        val condition = json.optString("condition", "Assessment required")
        if (condition.isBlank() || condition.length < 3) {
            warnings.add("Condition field was empty or too short")
        }

        // Extract treatment steps
        val treatment = extractStringArray(json, "treatment")
        if (treatment.isEmpty()) {
            warnings.add("No treatment steps provided")
        }

        // Extract next steps
        val nextSteps = extractStringArray(json, "next_steps")
        if (nextSteps.isEmpty()) {
            warnings.add("No next steps provided")
        }

        // Extract red flags
        val redFlags = extractStringArray(json, "red_flags")

        // Build disclaimer based on confidence
        val disclaimer = buildDisclaimer(confidence, warnings.isNotEmpty())

        val guidance = HealthGuidance(
            possibleCondition = condition,
            suggestedTreatment = treatment.ifEmpty {
                listOf("Perform full clinical assessment", "Monitor vital signs")
            },
            recommendedNextSteps = nextSteps.ifEmpty {
                listOf("Monitor patient and refer if symptoms worsen")
            },
            disclaimer = disclaimer,
            triageLevel = finalTriage,
            confidence = confidence,
            redFlags = redFlags,
        )

        return ParseResult(
            guidance = guidance,
            wasValid = warnings.isEmpty(),
            validationWarnings = warnings,
        )
    }

    private fun validateTriageCategory(raw: String): String {
        val normalized = raw.trim().lowercase()
        for (category in ClinicalPrompt.TRIAGE_CATEGORIES) {
            if (category.lowercase() == normalized) return category
        }
        // Fuzzy match
        return when {
            normalized.contains("emergency") || normalized.contains("refer") -> "Emergency referral"
            normalized.contains("urgent") -> "Urgent clinic visit"
            normalized.contains("routine") -> "Routine care"
            normalized.contains("home") -> "Home care"
            else -> "Urgent clinic visit" // default to caution
        }
    }

    private fun validateConfidence(raw: String): String {
        return when (raw.trim().lowercase()) {
            "high" -> "high"
            "medium", "moderate" -> "medium"
            "low" -> "low"
            else -> "low" // unknown confidence = assume low
        }
    }

    private fun buildDisclaimer(confidence: String, hasWarnings: Boolean): String {
        val base = "This guidance does not replace clinical judgment."
        return when {
            hasWarnings -> "Model output required correction. $base Verify with a clinician."
            confidence == "low" -> "Low confidence assessment. $base Refer to a clinician for confirmation."
            confidence == "medium" -> "Medium confidence. $base Monitor closely and refer if symptoms worsen."
            else -> base
        }
    }

    private fun buildUnstructuredFallback(rawResponse: String): HealthGuidance {
        val lines = rawResponse.lines().filter { it.isNotBlank() }.take(5)
        return HealthGuidance(
            possibleCondition = "Refer: unable to parse AI assessment",
            triageLevel = "Urgent clinic visit",
            confidence = "low",
            suggestedTreatment = if (lines.isNotEmpty()) {
                lines
            } else {
                listOf("Perform full clinical assessment", "Monitor vital signs")
            },
            recommendedNextSteps = listOf(
                "Refer to clinician. AI output could not be validated",
                "Monitor patient and reassess in 1-2 hours"
            ),
            disclaimer = "AI response could not be validated. Use clinical judgment and refer if uncertain."
        )
    }

    private fun extractStringArray(json: JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "").trim()
            if (item.isNotBlank()) {
                result.add(item)
            }
        }
        return result
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
