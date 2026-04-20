package com.google.ai.edge.gallery.healthdemo.data

/**
 * On-device PII redactor. Conservative regex patterns, anchored so they cannot
 * false-positive on clinical text like "BP 120/80", "Hb 11.2", "for 5 days",
 * "3-year-old", or "10mg/kg".
 *
 * Runs on every free-text field before it leaves the phone. Cloud NER is a
 * second pass for anything regex misses (names especially).
 *
 * Every pattern is anchored either by a specific keyword (TIN:, NSSF:, DOB:)
 * or a structural feature (4-digit year, +256/07 prefix, NIN's 14-char shape).
 */
object PiiRedactor {

    /**
     * Ugandan mobile: +256/256/0 followed by 7XX XXX XXX (any mix of -/space/dot).
     * Covers MTN (77/78), Airtel (70/75), UTL (71), Africell (79), Lycamobile (72).
     */
    private val PHONE_MOBILE = Regex(
        "(?<![\\d/.\\-])(?:\\+?256[\\s\\-.]?|0)7\\d{2}[\\s\\-.]?\\d{3}[\\s\\-.]?\\d{3}(?!\\d)"
    )

    /**
     * Ugandan fixed-line: +256/0 followed by 3X/4X area-code + 6 or 7 digits.
     * Catches the common Kampala 041X and regional 03XX landlines.
     */
    private val PHONE_LANDLINE = Regex(
        "(?<![\\d/.\\-])(?:\\+?256[\\s\\-.]?|0)[34]\\d[\\s\\-.]?\\d{3}[\\s\\-.]?\\d{3,4}(?!\\d)"
    )

    /** Ugandan NIN: C/A + M/F + 2 digits + 10 alphanumeric = 14 chars. */
    private val NIN = Regex(
        "(?<![A-Z0-9])[CA][MF]\\d{2}[A-Z0-9]{10}(?![A-Z0-9])"
    )

    /**
     * Ugandan passport: letter + 7 digits (A/B series). Word-boundary gated so
     * we don't munch lab codes like "B12".
     */
    private val PASSPORT = Regex(
        "(?<![A-Z0-9])[AB]\\d{7}(?![A-Z0-9])"
    )

    /** Standard email. */
    private val EMAIL = Regex(
        "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"
    )

    /**
     * Date with 4-digit year in DMY or MDY order (19xx/20xx).
     * Will NOT match BP 120/80 (80 isn't a year, 120 > 31), nor 5/3 ratios.
     */
    private val DATE_WITH_YEAR = Regex(
        "(?<!\\d)(?:0?[1-9]|[12]\\d|3[01])[/\\-](?:0?[1-9]|1[0-2])[/\\-](?:19|20)\\d{2}(?!\\d)"
    )

    /** ISO date: YYYY-MM-DD (year-first). Clinical systems often export this shape. */
    private val DATE_ISO = Regex(
        "(?<!\\d)(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])(?!\\d)"
    )

    /**
     * DOB-labelled short form catches any plausible date after "DOB:".
     * Accepts DMY or ISO. Lenient on separator to tolerate messy handwriting-to-text.
     */
    private val DOB_LABELED = Regex(
        "(?i)\\b(?:DOB|D\\.O\\.B|born|date\\s*of\\s*birth)[:\\s]+" +
        "(?:" +
        "(?:19|20)\\d{2}[-/](?:0?[1-9]|1[0-2])[-/](?:0?[1-9]|[12]\\d|3[01])" +  // ISO
        "|(?:0?[1-9]|[12]\\d|3[01])[/\\-](?:0?[1-9]|1[0-2])(?:[/\\-](?:\\d{2}|\\d{4}))?" +  // DMY, year optional
        ")"
    )

    /** TIN: 10 digits, keyword-gated. */
    private val TIN = Regex("(?i)\\bTIN[:\\s#]*\\d{10}\\b")

    /**
     * NSSF card number: 13 digits. Labelled form caught by keyword gate;
     * unlabelled numerics are likely to false-positive on lab IDs, so we keep
     * the gate but widen the separator set to include ":" and "No."
     */
    private val NSSF = Regex("(?i)\\bNSSF(?:\\s*(?:No|Number))?[:\\s#\\-]*\\d{13}\\b")

    /**
     * Redact PII in a free-text field. Returns (redacted_text, counts_by_type).
     * Use counts_by_type for telemetry — never log the actual text.
     */
    fun redact(input: String?): RedactionResult {
        if (input.isNullOrBlank()) return RedactionResult(input, emptyMap())
        val counts = mutableMapOf<String, Int>()
        // Explicit non-null type: smart-cast from isNullOrBlank doesn't survive
        // through the `var` into the nested `apply` closure that mutates it.
        var result: String = input

        fun apply(name: String, pattern: Regex, replacement: String) {
            val matches = pattern.findAll(result).count()
            if (matches > 0) {
                counts[name] = matches
                result = pattern.replace(result, replacement)
            }
        }

        // Order matters — most specific first. DOB_LABELED consumes the date after
        // the label so DATE_WITH_YEAR doesn't re-match what's already `[DOB]`.
        apply("nin", NIN, "[NIN]")
        apply("passport", PASSPORT, "[PASSPORT]")
        apply("email", EMAIL, "[EMAIL]")
        apply("phone", PHONE_MOBILE, "[PHONE]")
        apply("phone", PHONE_LANDLINE, "[PHONE]")
        apply("tin", TIN, "[TIN]")
        apply("nssf", NSSF, "[NSSF]")
        apply("dob", DOB_LABELED, "[DOB]")
        apply("date", DATE_ISO, "[DATE]")
        apply("date", DATE_WITH_YEAR, "[DATE]")

        return RedactionResult(result, counts)
    }
}

data class RedactionResult(
    val text: String?,
    val counts: Map<String, Int>
) {
    val didRedact: Boolean get() = counts.isNotEmpty()
}
