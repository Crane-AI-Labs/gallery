package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.llm.DeviceInfo
import com.google.ai.edge.gallery.llm.LlamaCpp

private const val TAG = "UgandaApiSync"

/**
 * Syncs local Room data to the Uganda API (tier_1_identified).
 *
 * Data lives on an Afriqloud VM in Kampala
 * (DPPA 2019 §19 — data sovereignty). Device diagnostics are included
 * for cross-phone optimisation.
 *
 * Free-text fields pass through [PiiRedactor] first — structured PII
 * (phone numbers, NINs, dates) is stripped on-device before the payload
 * ever leaves the handset. The server's ETL runs a second NER pass
 * (Microsoft Presidio) to catch names and other unstructured PII before
 * promoting records to tier_2_analytics.
 */
object UgandaApiSync {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // NET_CAPABILITY_VALIDATED means the OS's captive-portal probe
        // actually reached the internet. Without it, a captive-portal SSID
        // reports INTERNET=true and every POST hangs for the full 45s of
        // connect+read timeout — burning IO workers for no reason.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun buildDevice(context: Context): Map<String, Any?> = mapOf(
        "device_model" to Build.MODEL,
        "device_manufacturer" to Build.MANUFACTURER,
        "chipset" to DeviceInfo.chipset(),
        "android_api" to Build.VERSION.SDK_INT,
        "android_version" to Build.VERSION.RELEASE,
        "total_ram_mb" to DeviceInfo.totalRamMb(context),
        "max_cpu_freq_mhz" to (DeviceInfo.maxCpuFreqKhz() / 1000),
        "cpu_count" to DeviceInfo.onlineCpuCount(),
        "native_variant" to LlamaCpp.getLoadedVariant(),
        "perf_cores" to LlamaCpp.getPerfCoreInfo(),
        "recommended_n_batch" to DeviceInfo.recommendedNBatch(context),
        "app_version" to appVersion(context),
    )

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    fun syncAssessment(context: Context, assessment: SavedAssessment): Boolean {
        val symptomsRedaction = PiiRedactor.redact(assessment.symptoms)
        val notesRedaction = PiiRedactor.redact(assessment.referralInfo?.notes)

        if (symptomsRedaction.didRedact) {
            Log.d(TAG, "Regex redaction (symptoms): ${symptomsRedaction.counts}")
        }
        if (notesRedaction.didRedact) {
            Log.d(TAG, "Regex redaction (referral notes): ${notesRedaction.counts}")
        }

        val payload: Map<String, Any?> = mapOf(
            "id" to assessment.id,
            "timestamp" to assessment.timestamp,
            "role" to (assessment.role?.name ?: PatientRole.Other.name),
            "custom_role" to assessment.customRole,
            "symptoms" to symptomsRedaction.text,
            "duration_value" to assessment.durationValue,
            "duration_unit" to (assessment.durationUnit?.name ?: DurationUnit.Days.name),
            "age" to assessment.age?.label,
            "age_years" to assessment.ageYears,
            "age_months" to assessment.ageMonths,
            "sex" to assessment.sex?.label,
            "vital_signs" to mapOf(
                // VitalSigns evolved across releases:
                //   - pulseRate → heartRate (semantic rename); server still
                //     uses `pulse_rate` for the heart-rate reading.
                //   - Makerere #2 (2026-04-24): `bloodLoss` renamed to
                //     `bloodPressure`. We send it under the new server key
                //     `blood_pressure`. The old `blood_loss` key is still
                //     mirrored with the same value for one release so the
                //     server ETL can backfill without breaking historical
                //     aggregates that referenced `blood_loss`. TODO(server):
                //     after the ETL is updated, stop sending `blood_loss`.
                "temperature" to assessment.vitalSigns.temperature,
                "pulse_rate" to assessment.vitalSigns.heartRate,
                "respiratory_rate" to assessment.vitalSigns.respiratoryRate,
                "blood_pressure" to assessment.vitalSigns.bloodPressure,
                "blood_loss" to assessment.vitalSigns.bloodPressure,
                "spo2" to assessment.vitalSigns.spO2,
            ),
            "confirmed_signs" to assessment.confirmedSigns.toList(),
            // Makerere v2 #4 (2026-04-24): traditional medicine enum +
            // free-text detail. Redact first so any phone numbers / names
            // inadvertently entered in the description don't leave the
            // device as-is.
            "traditional_medicine" to assessment.traditionalMedicine?.name,
            "traditional_medicine_details" to PiiRedactor.redact(assessment.traditionalMedicineDetails).text,
            // 3.6 (July 2026 pipeline note): what the worker actually did /
            // prescribed — captured in the UI since April but never synced.
            // Free text, so redact like the other free-text fields.
            "treatment_administered" to PiiRedactor.redact(assessment.treatmentAdministered).text,
            "guidance" to mapOf(
                "triage_level" to assessment.guidance.triageLevel,
                // Keep `condition` as the primary (most-likely) for
                // compatibility with the existing server ETL, which still
                // indexes on this field. New `conditions` carries the
                // full ranked differential list (1-3 entries).
                // TODO(server): once the ETL starts reading `conditions`,
                // it can index/aggregate on the full list and eventually
                // stop reading `condition` as a separate field.
                "condition" to assessment.guidance.possibleCondition,
                "conditions" to assessment.guidance.possibleConditions,
                "confidence" to assessment.guidance.confidence,
                "treatment" to assessment.guidance.suggestedTreatment,
                "next_steps" to assessment.guidance.recommendedNextSteps,
                "red_flags" to assessment.guidance.redFlags,
            ),
            "clinician_confirmation" to assessment.clinicianConfirmation?.let { c ->
                mapOf(
                    "guidance_used" to c.guidanceUsed?.name,
                    "final_action" to c.finalAction?.name,
                    "issue_tags" to c.issueTags.mapNotNull { it?.name },
                )
            },
            "referral" to assessment.referralInfo?.let { r ->
                mapOf(
                    "urgency" to (r.urgency?.name ?: "Routine"),
                    "destination" to r.destination?.name,
                    "reasons" to r.reasons.mapNotNull { it?.name },
                    "notes" to notesRedaction.text,
                )
            },
            "location" to mapOf(
                "latitude" to assessment.latitude,
                "longitude" to assessment.longitude,
                "accuracy_meters" to assessment.locationAccuracyMeters,
                "district" to assessment.district,
            ),
            "device" to buildDevice(context),
            // Wall-clock generation latency (ms). Null on rows saved
            // before migration 6→7 — server treats null as unknown.
            "inference_ms" to assessment.inferenceMs,
            // July 2026 pipeline note:
            // 3.1 — worker flagged the AI guidance as concerning.
            "guidance_concern" to assessment.guidanceConcern,
            // 3.5 — time to first token (ms) + retry count for the run.
            "ttft_ms" to assessment.ttftMs,
            "inference_retries" to assessment.inferenceRetries,
            // 3.8 — device-level test-traffic flag (Settings toggle).
            "is_test" to AppSettings.isTestDevice(context),
        )

        val ok = UgandaApi.postJson(context, "/assessments", payload)
        if (ok) Log.d(TAG, "Synced assessment ${assessment.id}")
        else Log.w(TAG, "Failed to sync assessment ${assessment.id}")
        return ok
    }

    fun syncPaused(context: Context, paused: PausedConsultation): Boolean {
        val symptomsRedaction = PiiRedactor.redact(paused.symptoms)
        val noteRedaction = PiiRedactor.redact(paused.note)

        val payload: Map<String, Any?> = mapOf(
            "id" to paused.id,
            "timestamp" to paused.timestamp,
            "role" to paused.role?.name,
            "symptoms" to symptomsRedaction.text,
            "age" to paused.age?.label,
            "age_years" to paused.ageYears,
            "age_months" to paused.ageMonths,
            "pause_reason" to paused.pauseReason?.name,
            "note" to noteRedaction.text,
            "device" to buildDevice(context),
        )

        val ok = UgandaApi.postJson(context, "/paused_consultations", payload)
        if (ok) Log.d(TAG, "Synced paused ${paused.id}")
        else Log.w(TAG, "Failed to sync paused ${paused.id}")
        return ok
    }

    fun removePausedFromCloud(id: String, context: Context) {
        val ok = UgandaApi.postJson(context, "/paused_consultations/delete", mapOf("id" to id))
        if (ok) Log.d(TAG, "Removed paused $id from cloud") else Log.w(TAG, "Failed to remove paused $id")
    }

    /**
     * DPPA §7 — right-to-erasure. Calls the server's /delete_my_data endpoint.
     * Returns true only on server-side acknowledgement; local wipe is done
     * by the caller irrespective of the outcome so the user's intent is
     * honoured on their device even if the network failed.
     */
    fun deleteMyData(context: Context): Boolean {
        if (!isOnline(context)) {
            Log.w(TAG, "Offline — cannot request server-side erasure")
            return false
        }
        return UgandaApi.deleteMyData(context)
    }

    /**
     * Send device diagnostics snapshot — useful for ops to see which handsets
     * are online and on which app versions even when no assessment has been saved.
     */
    fun syncDeviceDiagnostics(context: Context) {
        val payload = buildDevice(context) + mapOf(
            "device_id" to (UgandaApi.getDeviceId(context) ?: "unknown"),
            "synced_at" to System.currentTimeMillis(),
        )
        val ok = UgandaApi.postJson(context, "/device_diagnostics", payload)
        if (ok) Log.d(TAG, "Synced device diagnostics") else Log.w(TAG, "Failed to sync diagnostics")
    }
}
