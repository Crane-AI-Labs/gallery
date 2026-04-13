package com.google.ai.edge.gallery.healthdemo.data

import android.util.Log

private const val TAG = "GuidanceValidator"

/**
 * Validates and parses MedGemma XML output into safe, structured HealthGuidance.
 *
 * Guardrails:
 * 1. XML tag extraction — parses <r><t>...<rf>...</rf></r> format
 * 2. Triage category validation — must be one of the predefined categories
 * 3. Confidence threshold — auto-refer if "low" or parsing fails
 * 4. L2 dosage safety — strips drug names/dosages from output (post-processing)
 * 5. Fallback for malformed output — extract what we can, flag for review
 */
object GuidanceValidator {

    data class ParseResult(
        val guidance: HealthGuidance,
        val wasValid: Boolean,
        val validationWarnings: List<String> = emptyList(),
    )

    /**
     * Parse and validate a raw LLM response into HealthGuidance.
     * Supports both XML format (primary) and JSON format (legacy fallback).
     * Always returns a result — never throws.
     */
    fun parseAndValidate(rawResponse: String): ParseResult {
        val warnings = mutableListOf<String>()

        // Try XML first (primary format)
        val xmlResult = parseXml(rawResponse)
        if (xmlResult != null) {
            return buildResult(xmlResult, rawResponse, warnings)
        }

        // Fall back to JSON (legacy support)
        val jsonResult = parseJson(rawResponse)
        if (jsonResult != null) {
            warnings.add("Response was JSON (legacy format), not XML")
            return buildResult(jsonResult, rawResponse, warnings)
        }

        Log.w(TAG, "No XML or JSON found in response")
        return ParseResult(
            guidance = buildUnstructuredFallback(rawResponse),
            wasValid = false,
            validationWarnings = listOf("Could not extract structured data from model response"),
        )
    }

    private data class RawFields(
        val triage: String,
        val condition: String,
        val confidence: String,
        val treatment: List<String>,
        val nextSteps: List<String>,
        val redFlags: List<String>,
    )

    // ─── XML Parsing ─────────────────────────────────────────────────────────────

    private fun parseXml(response: String): RawFields? {
        // Look for <r>...</r> or just the individual tags
        val text = response.trim()

        val triage = extractTag(text, "t") ?: return null
        val condition = extractTag(text, "c") ?: ""
        val confidence = extractTag(text, "cf") ?: "low"
        val treatment = extractTag(text, "tx")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val nextSteps = extractTag(text, "ns")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val redFlags = extractTag(text, "rf")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        return RawFields(triage, condition, confidence, treatment, nextSteps, redFlags)
    }

    private fun extractTag(text: String, tag: String): String? {
        val pattern = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(text)?.groupValues?.get(1)?.trim()
    }

    // ─── JSON Parsing (legacy fallback) ──────────────────────────────────────────

    private fun parseJson(response: String): RawFields? {
        val jsonStr = extractJsonBlock(response) ?: return null
        val json = try {
            org.json.JSONObject(jsonStr)
        } catch (e: Exception) {
            return null
        }

        return RawFields(
            triage = json.optString("triage", ""),
            condition = json.optString("condition", ""),
            confidence = json.optString("confidence", "low"),
            treatment = extractJsonArray(json, "treatment"),
            nextSteps = extractJsonArray(json, "next_steps"),
            redFlags = extractJsonArray(json, "red_flags"),
        )
    }

    private fun extractJsonBlock(text: String): String? {
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

    private fun extractJsonArray(json: org.json.JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "").trim()
            if (item.isNotBlank()) result.add(item)
        }
        return result
    }

    // ─── Validation & Safety ─────────────────────────────────────────────────────

    private fun buildResult(fields: RawFields, rawResponse: String, warnings: MutableList<String>): ParseResult {
        // Validate triage
        val triage = validateTriageCategory(fields.triage)
        if (triage != fields.triage.trim()) {
            warnings.add("Triage '${fields.triage}' normalized to '$triage'")
        }

        // Validate confidence
        val confidence = validateConfidence(fields.confidence)
        if (confidence != fields.confidence.trim().lowercase()) {
            warnings.add("Confidence '${fields.confidence}' normalized to '$confidence'")
        }

        // Auto-escalate low confidence home care
        val finalTriage = if (confidence == "low" && triage == "Home care") {
            warnings.add("Low confidence + Home care auto-escalated to Urgent clinic visit")
            "Urgent clinic visit"
        } else triage

        // L2 dosage safety: strip drug names and dosages from all text fields
        val safeTreatment = fields.treatment.map { sanitizeDosage(it, warnings) }
        val safeNextSteps = fields.nextSteps.map { sanitizeDosage(it, warnings) }
        val safeCondition = sanitizeDosage(fields.condition, warnings)

        val guidance = HealthGuidance(
            possibleCondition = safeCondition.ifBlank { "Assessment required" },
            suggestedTreatment = safeTreatment.filter { it.isNotBlank() }.ifEmpty {
                listOf("Perform full clinical assessment", "Monitor vital signs")
            },
            recommendedNextSteps = safeNextSteps.filter { it.isNotBlank() }.ifEmpty {
                listOf("Monitor patient and refer if symptoms worsen")
            },
            disclaimer = buildDisclaimer(confidence, warnings.isNotEmpty()),
            triageLevel = finalTriage,
            confidence = confidence,
            redFlags = fields.redFlags,
        )

        return ParseResult(guidance, warnings.isEmpty(), warnings)
    }

    // ─── L2 Dosage Safety (post-processing) ──────────────────────────────────────
    // These regexes catch drug names and dosages that the model generates despite
    // being told not to. Defense in depth — the model's training handles most cases,
    // these catch the rest.

    private val UCG_REPLACEMENT = "Refer to Uganda Clinical Guidelines 2023"

    /** L2-051: Numeric dose patterns (500mg, 10mg/kg, 2 tablets, etc.) */
    private val NUMERIC_DOSE_PATTERN = Regex(
        """\b\d+(?:\.\d+)?\s*(?:mg|mcg|microgram|milligram|gram|ml|millilit(?:re|er)|iu|units?|mmol|mEq|tabs?|tablets?|caps?|capsules?|sachets?)(?:\s*/\s*kg)?(?:\s*(?:per\s+|/\s*)(?:day|hr|hour|dose|kg))?\b""",
        RegexOption.IGNORE_CASE
    )

    /** L2-050: Drug names from UCG 2023 formulary (top offenders from testing) */
    private val DRUG_NAME_PATTERN = Regex(
        """\b(?:amoxicillin|amoxycillin|ampicillin|artesunate|artemether|azithromycin|ceftriaxone|chloramphenicol|ciprofloxacin|cloxacillin|cotrimoxazole|co-trimoxazole|dexamethasone|diazepam|diclofenac|doxycycline|erythromycin|fluconazole|gentamicin|gentamycin|hydrocortisone|ibuprofen|lumefantrine|mebendazole|albendazole|metformin|metoclopramide|metronidazole|morphine|nitrofurantoin|nystatin|omeprazole|paracetamol|penicillin|phenobarbital|prednisolone|quinine|salbutamol|sulfamethoxazole|trimethoprim|vancomycin|warfarin)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strip drug names and numeric dosages from a text field.
     * Replaces the offending sentence with a UCG reference.
     */
    private fun sanitizeDosage(text: String, warnings: MutableList<String>): String {
        var result = text

        if (DRUG_NAME_PATTERN.containsMatchIn(result)) {
            val match = DRUG_NAME_PATTERN.find(result)!!.value
            warnings.add("L2-050 BLOCK: drug name '$match' stripped")
            // Replace the entire sentence containing the drug name
            result = replaceSentence(result, DRUG_NAME_PATTERN, UCG_REPLACEMENT)
        }

        if (NUMERIC_DOSE_PATTERN.containsMatchIn(result)) {
            val match = NUMERIC_DOSE_PATTERN.find(result)!!.value
            warnings.add("L2-051 BLOCK: numeric dose '$match' stripped")
            result = replaceSentence(result, NUMERIC_DOSE_PATTERN, UCG_REPLACEMENT)
        }

        return result
    }

    /** Replace the sentence containing a regex match, preserving the rest of the text. */
    private fun replaceSentence(text: String, pattern: Regex, replacement: String): String {
        // If the whole text is one sentence (no period/pipe separators), replace entirely
        if (!text.contains("|") && !text.contains(". ")) {
            return replacement
        }
        // Split by pipe (XML format) or period, replace offending segments
        val separator = if (text.contains("|")) "|" else ". "
        val parts = text.split(separator).map { part ->
            if (pattern.containsMatchIn(part)) replacement else part
        }.distinct() // Remove duplicate replacements
        return parts.joinToString(separator)
    }

    // ─── Validators ──────────────────────────────────────────────────────────────

    private fun validateTriageCategory(raw: String): String {
        val normalized = raw.trim().lowercase()
        for (category in ClinicalPrompt.TRIAGE_CATEGORIES) {
            if (category.lowercase() == normalized) return category
        }
        return when {
            normalized.contains("emergency") || normalized.contains("refer") -> "Emergency referral"
            normalized.contains("urgent") -> "Urgent clinic visit"
            normalized.contains("routine") -> "Routine care"
            normalized.contains("home") -> "Home care"
            else -> "Urgent clinic visit"
        }
    }

    private fun validateConfidence(raw: String): String {
        return when (raw.trim().lowercase()) {
            "high" -> "high"
            "medium", "moderate" -> "medium"
            "low" -> "low"
            else -> "low"
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
            suggestedTreatment = if (lines.isNotEmpty()) lines
                else listOf("Perform full clinical assessment", "Monitor vital signs"),
            recommendedNextSteps = listOf(
                "Refer to clinician. AI output could not be validated",
                "Monitor patient and reassess in 1-2 hours"
            ),
            disclaimer = "AI response could not be validated. Use clinical judgment and refer if uncertain."
        )
    }
}
