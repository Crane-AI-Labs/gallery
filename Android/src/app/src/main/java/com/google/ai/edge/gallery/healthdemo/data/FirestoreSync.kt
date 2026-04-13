package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.llm.DeviceInfo
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private const val TAG = "FirestoreSync"
private const val COLLECTION_ASSESSMENTS = "assessments"
private const val COLLECTION_PAUSED = "paused_consultations"
private const val COLLECTION_DIAGNOSTICS = "device_diagnostics"

/**
 * Syncs local Room data to Firestore when online.
 * Each record includes device diagnostics for remote debugging.
 *
 * Firestore structure:
 *   assessments/{id} — full assessment data + device info
 *   paused_consultations/{id} — paused consultation data
 *   device_diagnostics/{deviceId} — latest device state for debugging
 */
object FirestoreSync {

    private var firestore: FirebaseFirestore? = null

    private fun db(): FirebaseFirestore? {
        if (firestore == null) {
            firestore = try {
                FirebaseFirestore.getInstance()
            } catch (e: Exception) {
                Log.w(TAG, "Firestore not available: ${e.message}")
                null
            }
        }
        return firestore
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Build device diagnostics map for remote debugging.
     * Included with every synced record so field issues can be traced to device type.
     */
    fun buildDeviceDiagnostics(context: Context): Map<String, Any> {
        return mapOf(
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
            "app_version" to getAppVersion(context),
            "synced_at" to System.currentTimeMillis()
        )
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    // ─── Sync Assessment ─────────────────────────────────────────────────────────

    fun syncAssessment(context: Context, assessment: SavedAssessment) {
        val db = db() ?: return

        val data = mutableMapOf<String, Any>(
            "id" to assessment.id,
            "timestamp" to assessment.timestamp,
            "role" to assessment.role.name,
            "custom_role" to assessment.customRole,
            "symptoms" to assessment.symptoms,
            "duration_value" to assessment.durationValue,
            "duration_unit" to assessment.durationUnit.name,
            "age" to (assessment.age?.label ?: ""),
            "sex" to (assessment.sex?.label ?: ""),
            "vital_signs" to mapOf(
                "temperature" to assessment.vitalSigns.temperature,
                "pulse_rate" to assessment.vitalSigns.pulseRate,
                "blood_pressure" to assessment.vitalSigns.bloodPressure,
                "respiratory_rate" to assessment.vitalSigns.respiratoryRate
            ),
            "confirmed_signs" to assessment.confirmedSigns.toList(),
            "location" to mapOf(
                "latitude" to (assessment.latitude ?: ""),
                "longitude" to (assessment.longitude ?: ""),
                "accuracy_meters" to (assessment.locationAccuracyMeters ?: ""),
                "district" to (assessment.district ?: "")
            ),
            "guidance" to mapOf(
                "triage_level" to assessment.guidance.triageLevel,
                "condition" to assessment.guidance.possibleCondition,
                "confidence" to assessment.guidance.confidence,
                "treatment" to assessment.guidance.suggestedTreatment,
                "next_steps" to assessment.guidance.recommendedNextSteps,
                "red_flags" to assessment.guidance.redFlags,
                "disclaimer" to assessment.guidance.disclaimer
            ),
            "device" to buildDeviceDiagnostics(context)
        )

        // Add clinician confirmation if present
        assessment.clinicianConfirmation?.let { conf ->
            data["clinician_confirmation"] = mapOf(
                "understood" to conf.understood,
                "guidance_used" to (conf.guidanceUsed?.name ?: ""),
                "final_action" to (conf.finalAction?.name ?: ""),
                "issue_tags" to conf.issueTags.map { it.name }
            )
        }

        // Add referral if present
        assessment.referralInfo?.let { ref ->
            data["referral"] = mapOf(
                "urgency" to ref.urgency.name,
                "destination" to (ref.destination?.name ?: ""),
                "reasons" to ref.reasons.map { it.name },
                "notes" to ref.notes
            )
        }

        db.collection(COLLECTION_ASSESSMENTS)
            .document(assessment.id)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Synced assessment ${assessment.id}") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to sync assessment ${assessment.id}", e) }
    }

    // ─── Sync Paused Consultation ────────────────────────────────────────────────

    fun syncPaused(context: Context, paused: PausedConsultation) {
        val db = db() ?: return

        val data = mapOf(
            "id" to paused.id,
            "timestamp" to paused.timestamp,
            "role" to paused.role.name,
            "symptoms" to paused.symptoms,
            "age" to (paused.age?.label ?: ""),
            "pause_reason" to (paused.pauseReason?.name ?: ""),
            "note" to paused.note,
            "device" to buildDeviceDiagnostics(context)
        )

        db.collection(COLLECTION_PAUSED)
            .document(paused.id)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Synced paused ${paused.id}") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to sync paused ${paused.id}", e) }
    }

    fun removePausedFromCloud(id: String) {
        val db = db() ?: return
        db.collection(COLLECTION_PAUSED)
            .document(id)
            .delete()
            .addOnSuccessListener { Log.d(TAG, "Removed paused $id from cloud") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to remove paused $id", e) }
    }

    // ─── Sync Device Diagnostics ─────────────────────────────────────────────────

    fun syncDeviceDiagnostics(context: Context) {
        val db = db() ?: return
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")

        db.collection(COLLECTION_DIAGNOSTICS)
            .document(deviceId)
            .set(buildDeviceDiagnostics(context), SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Synced device diagnostics for $deviceId") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to sync diagnostics", e) }
    }
}
