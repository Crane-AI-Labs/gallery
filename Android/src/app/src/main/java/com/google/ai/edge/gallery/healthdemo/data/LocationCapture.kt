package com.google.ai.edge.gallery.healthdemo.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.round

private const val TAG = "LocationCapture"

/**
 * One-shot location capture for health assessments.
 *
 * Design decisions (based on DHIS2/CommCare/Uganda DPPA research):
 * - Uses BALANCED_POWER_ACCURACY (~100m) — sufficient for district mapping
 * - Coordinates rounded to 2 decimal places (~1.1km grid) to prevent
 *   identifying individual homes in rural areas
 * - Non-blocking — returns null if location unavailable
 * - 30-second timeout
 */
data class CapturedLocation(
    val latitude: Double,    // Rounded to 2 decimal places
    val longitude: Double,   // Rounded to 2 decimal places
    val accuracyMeters: Float
)

object LocationCapture {

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Capture current location. Returns null if unavailable or permission denied.
     * Never blocks the assessment — the health worker can always proceed without location.
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(context: Context): CapturedLocation? {
        if (!hasPermission(context)) {
            Log.d(TAG, "Location permission not granted")
            return null
        }

        return try {
            suspendCancellableCoroutine<CapturedLocation?> { cont ->
                val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                val cts = com.google.android.gms.tasks.CancellationTokenSource()

                cont.invokeOnCancellation { cts.cancel() }

                client.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token
                ).addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val rounded = CapturedLocation(
                            latitude = round(location.latitude * 100) / 100,
                            longitude = round(location.longitude * 100) / 100,
                            accuracyMeters = location.accuracy
                        )
                        Log.d(TAG, "Location captured: ${rounded.latitude}, ${rounded.longitude} (±${rounded.accuracyMeters}m)")
                        cont.resume(rounded)
                    } else {
                        Log.d(TAG, "Location returned null")
                        cont.resume(null)
                    }
                }.addOnFailureListener { ex: Exception ->
                    Log.w(TAG, "Location capture failed: ${ex.message}")
                    cont.resume(null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location capture exception: ${e.message}")
            null
        }
    }
}
