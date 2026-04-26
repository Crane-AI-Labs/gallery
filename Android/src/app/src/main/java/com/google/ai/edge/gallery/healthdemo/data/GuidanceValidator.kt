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
     *
     * `wasValid` reflects whether the model output was structurally parseable
     * into the expected XML/JSON schema. Post-hoc safety overrides (danger
     * sign → Emergency referral, low-confidence auto-escalation, dosage
     * stripping) are NOT considered parse failures — Makerere #4 hang was
     * caused by the ViewModel retrying every parse with warnings, which
     * always fires for danger-sign cases. Callers that care about overrides
     * can still inspect `validationWarnings`.
     */
    fun parseAndValidate(
        rawResponse: String,
        confirmedSigns: Set<String> = emptySet()
    ): ParseResult {
        val warnings = mutableListOf<String>()

        // Try XML first (primary format)
        val xmlResult = parseXml(rawResponse)
        if (xmlResult != null) {
            return buildResult(xmlResult, rawResponse, warnings, confirmedSigns, parsedCleanly = true)
        }

        // Fall back to JSON (legacy support)
        val jsonResult = parseJson(rawResponse)
        if (jsonResult != null) {
            warnings.add("Response was JSON (legacy format), not XML")
            // JSON is a degraded but usable format — still a successful parse.
            return buildResult(jsonResult, rawResponse, warnings, confirmedSigns, parsedCleanly = true)
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
        // dedupBullets: the model occasionally emits the same instruction twice
        // (Makerere field test #A26-2 saw "IV fluids" rendered twice on one
        // case). Distinct on trimmed lowercase form so trivial casing/whitespace
        // differences also collapse.
        val treatment = extractTag(text, "tx")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }?.let(::dedupBullets) ?: emptyList()
        val nextSteps = extractTag(text, "ns")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }?.let(::dedupBullets) ?: emptyList()
        val redFlags = extractTag(text, "rf")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }?.let(::dedupBullets) ?: emptyList()

        return RawFields(triage, condition, confidence, treatment, nextSteps, redFlags)
    }

    private fun extractTag(text: String, tag: String): String? {
        val pattern = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        // The XML payload occasionally contains HTML-entity-escaped content
        // because the model writes "<" inside strings (e.g. "SpO2 < 90%"),
        // which our prompt-format expectations interpret as XML. Decoding
        // here keeps the rendered text readable instead of showing
        // "SpO2 &lt; 90%" verbatim in the guidance UI.
        return pattern.find(text)?.groupValues?.get(1)?.let(::decodeHtmlEntities)?.trim()
    }

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            // &amp; last so we don't accidentally double-decode &amp;lt; → "<"
            .replace("&amp;", "&")

    private fun dedupBullets(items: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return items.filter { seen.add(it.trim().lowercase()) }
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

    private fun buildResult(
        fields: RawFields,
        rawResponse: String,
        warnings: MutableList<String>,
        confirmedSigns: Set<String> = emptySet(),
        parsedCleanly: Boolean = false,
    ): ParseResult {
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

        // CRITICAL SAFETY: If health worker confirmed any danger sign,
        // force Emergency referral regardless of what the model says.
        // A human clinical observation overrides AI output.
        val hasConfirmedDangerSign = confirmedSigns.any { it in CRITICAL_DANGER_SIGNS }

        val finalTriage = when {
            hasConfirmedDangerSign -> {
                val signs = confirmedSigns.filter { it in CRITICAL_DANGER_SIGNS }
                warnings.add("SAFETY OVERRIDE: Confirmed danger sign(s) [${signs.joinToString()}] → Emergency referral")
                "Emergency referral"
            }
            confidence == "low" && triage == "Home care" -> {
                warnings.add("Low confidence + Home care auto-escalated to Urgent clinic visit")
                "Urgent clinic visit"
            }
            confidence == "low" && triage == "Routine care" -> {
                warnings.add("Low confidence + Routine care auto-escalated to Urgent clinic visit")
                "Urgent clinic visit"
            }
            else -> triage
        }

        // L2 dosage safety: keep drug names (health workers need them),
        // strip only numeric dosages (the dangerous part), clean up empty results
        val safeTreatment = fields.treatment
            .map { stripDosageOnly(it, warnings) }
            .filter { it.isNotBlank() && it.length > 3 }
        val safeNextSteps = fields.nextSteps
            .map { stripDosageOnly(it, warnings) }
            .filter { it.isNotBlank() && it.length > 3 }

        // <c> is now pipe-delimited (most-likely first, up to 3 entries).
        // Split, trim, drop blanks. Primary condition stays in
        // `possibleCondition` for backward compatibility (server ETL and
        // legacy UI). Full ranked list lives in `possibleConditions`.
        val conditions = fields.condition
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val primaryCondition = conditions.firstOrNull() ?: ""

        val guidance = HealthGuidance(
            possibleCondition = primaryCondition.ifBlank { "Assessment required" },
            possibleConditions = conditions,
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

        // wasValid is true whenever the model output parsed cleanly, even if
        // we then rewrote the triage level (safety override) or stripped a
        // dosage string. Those are post-hoc safeguards, not parse failures —
        // flagging them as invalid used to trigger infinite retry loops on
        // any case with confirmed danger signs (Makerere #4).
        return ParseResult(guidance, parsedCleanly, warnings)
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
     * Strip numeric dosages only — keep drug names (health workers need to know
     * WHAT medication, just not HOW MUCH). Replace dosage with "as per UCG guidelines".
     *
     * Examples:
     *   "Give amoxicillin 500mg twice daily" → "Give amoxicillin as per UCG guidelines"
     *   "Administer artesunate 10mg/kg IV" → "Administer artesunate as per UCG guidelines"
     *   "Perform malaria RDT" → "Perform malaria RDT" (no change)
     *   "Give ORS in small sips" → "Give ORS in small sips" (no change, ORS has no dose)
     */
    private fun stripDosageOnly(text: String, warnings: MutableList<String>): String {
        if (!NUMERIC_DOSE_PATTERN.containsMatchIn(text)) return text

        val match = NUMERIC_DOSE_PATTERN.find(text)!!.value
        warnings.add("L2-051: dosage '$match' replaced with UCG reference")

        // Replace the dosage and any trailing frequency (e.g. "twice daily", "every 6 hours", "for 5 days")
        var result = NUMERIC_DOSE_PATTERN.replace(text, "as per UCG guidelines")

        // Clean up: remove duplicate spaces, trailing frequency phrases that are now orphaned
        result = result.replace(Regex("as per UCG guidelines\\s+(twice|thrice|once|three times|four times)\\s+(daily|a day)"), "as per UCG guidelines")
        result = result.replace(Regex("as per UCG guidelines\\s+every\\s+\\d+\\s+hours?"), "as per UCG guidelines")
        result = result.replace(Regex("as per UCG guidelines\\s+for\\s+\\d+\\s+(days?|weeks?)"), "as per UCG guidelines")
        result = result.replace(Regex("\\s{2,}"), " ").trim()

        return result
    }

    // ─── Validators ──────────────────────────────────────────────────────────────

    private fun validateTriageCategory(raw: String): String {
        // Strip markdown emphasis the model sometimes wraps the label in,
        // e.g. "**Emergency referral**" or "*** Urgent ***". Without this the
        // exact-match below misses and we fall through to the looser keyword
        // path below.
        val normalized = raw.trim().trim('*', ' ').lowercase()
        for (category in ClinicalPrompt.TRIAGE_CATEGORIES) {
            if (category.lowercase() == normalized) return category
        }
        // Order matters: check the specific labels before the looser keywords
        // so "urgent referral" → Urgent (not Emergency). Previously a plain
        // contains("refer") sent every sentence mentioning a referral
        // (including legitimate Routine/Home cases that referenced "refer
        // back to the clinician") into Emergency referral, which is the
        // single biggest contributor to the Emergency-everywhere pattern
        // the field testers reported.
        return when {
            normalized.contains("emergency referral") || normalized.contains("emergency") -> "Emergency referral"
            normalized.contains("urgent clinic visit") || normalized.contains("urgent") -> "Urgent clinic visit"
            normalized.contains("routine care") || normalized.contains("routine") -> "Routine care"
            normalized.contains("home care") || normalized.contains("home") -> "Home care"
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
        // SAFETY: Never show raw LLM output as clinical advice.
        // If we can't parse the response, show only safe generic instructions.
        return HealthGuidance(
            possibleCondition = "Refer: unable to parse AI assessment",
            triageLevel = "Urgent clinic visit",
            confidence = "low",
            suggestedTreatment = listOf(
                "Perform full clinical assessment using standard protocols",
                "Use the Uganda Clinical Guidelines 2023 for treatment decisions"
            ),
            recommendedNextSteps = listOf(
                "Refer to a clinician for proper evaluation",
                "Monitor patient closely and reassess"
            ),
            disclaimer = "The AI could not generate a valid assessment. Use your clinical judgment and refer if uncertain."
        )
    }
}
