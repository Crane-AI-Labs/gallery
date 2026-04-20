package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UgandaApi"

/**
 * Thin client for the Uganda ingestion API (Afriqloud VM, Kampala).
 * Uses HttpURLConnection to avoid pulling a new dependency.
 *
 * Enrollment: call /enroll once to get a signed device token. The server
 * HMACs a UUID with DEVICE_TOKEN_SECRET and returns `<uuid>.<sig>`.
 * We store that token and send it as Bearer on every subsequent POST.
 */
object UgandaApi {
    private const val ENCRYPTED_PREFS = "ease_health_uganda_api_secure"
    // Retained only for one-shot migration from the unencrypted prefs file
    // used in earlier builds. Cleared after migration.
    private const val LEGACY_PREFS = "ease_health_uganda_api"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    /**
     * Set when the server rejects our token with 401 in a way we didn't
     * initiate (i.e. not via /delete_my_data). Blocks silent re-enrolment
     * so a rotated server secret or ops-side revocation doesn't cause
     * every queued assessment to be re-attributed to a new device_id —
     * which would break the tier_1 audit trail.
     *
     * Cleared by [resetIdentity] (invoked from Settings → Reset sync, or
     * from /delete_my_data success) so the next sync re-enrols cleanly.
     */
    private const val KEY_SYNC_DISABLED = "sync_disabled"

    /**
     * Base URL. Uses HTTPS through nginx on the Kampala VM. `/api/` prefix
     * maps to the FastAPI service behind the proxy.
     *
     * Uses the VM's raw IP because `easehealth.afriqloud.cloud` has no A
     * record yet. The TLS cert's SAN covers both the IP and the hostname,
     * so swapping to the hostname once DNS lands is a one-line change plus
     * an SPKI pin rotation (same cert, same pin — just the host changes).
     */
    const val BASE_URL = "https://41.220.3.234/api"

    private val gson = Gson()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * EncryptedSharedPreferences-backed store for the device token.
     * Falls back to plain MODE_PRIVATE if the AndroidX security-crypto setup
     * fails on a given device (rare, but e.g. hardware-backed keystore missing
     * on very old OEM ROMs) — the app has to keep working in that case, the
     * token just lives unencrypted in app-private storage.
     */
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val appCtx = context.applicationContext
            val result: SharedPreferences = try {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encrypted = EncryptedSharedPreferences.create(
                    appCtx,
                    ENCRYPTED_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                migrateLegacyIfPresent(appCtx, encrypted)
                encrypted
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable; falling back to MODE_PRIVATE: ${e.message}")
                appCtx.getSharedPreferences(ENCRYPTED_PREFS, Context.MODE_PRIVATE)
            }
            cachedPrefs = result
            return result
        }
    }

    /** Move any token written by older builds into the encrypted store, then wipe the plain one. */
    private fun migrateLegacyIfPresent(context: Context, encrypted: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val token = legacy.getString(KEY_TOKEN, null) ?: return
        if (encrypted.getString(KEY_TOKEN, null) == null) {
            encrypted.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_DEVICE_ID, legacy.getString(KEY_DEVICE_ID, null))
                .apply()
            Log.d(TAG, "Migrated device token from plain prefs to encrypted store")
        }
        legacy.edit().clear().apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun getDeviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    /** True when the server has rejected our token and we have not yet been told to re-enrol. */
    fun isSyncDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYNC_DISABLED, false)

    /**
     * Clear the cached token. Called internally after /delete_my_data success
     * (there, we want the device to re-enrol on next sync, same as install).
     * NOT called on unexpected 401s — that path goes through [markSyncDisabled]
     * instead, to preserve audit-trail provenance for queued assessments.
     */
    private fun clearToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_SYNC_DISABLED)
            .apply()
    }

    /**
     * Mark sync as disabled. The token + device_id are kept so the UI can
     * display which device identity is currently blocked, but [ensureEnrolled]
     * will refuse to mint a replacement until the user takes explicit action.
     */
    private fun markSyncDisabled(context: Context) {
        prefs(context).edit().putBoolean(KEY_SYNC_DISABLED, true).apply()
    }

    /**
     * Public: wipe this device's identity and re-enable sync. Used from
     * Settings → Reset sync and from /delete_my_data success. The next sync
     * attempt will re-enrol with a fresh device_id.
     */
    fun resetIdentity(context: Context) {
        clearToken(context)
    }

    /**
     * DPPA §7 — right to erasure. Posts /delete_my_data and, on 2xx,
     * clears the device identity so the next sync re-enrols with a fresh UUID.
     */
    fun deleteMyData(context: Context): Boolean {
        val ok = postJson(context, "/delete_my_data", emptyMap())
        if (ok) {
            clearToken(context)
            Log.d(TAG, "Server erasure acknowledged; device identity cleared")
        }
        return ok
    }

    /**
     * Enroll with the server — blocking. Call from IO thread.
     * Safe to call repeatedly; returns the cached token if already enrolled.
     * Synchronized because cold-start fires two concurrent enroll paths
     * (GalleryApplication.onCreate + HealthDemoRepository.init) and we only
     * want one /enroll POST per install.
     */
    @Synchronized
    fun ensureEnrolled(context: Context): String? {
        if (isSyncDisabled(context)) {
            // Token was rejected; caller must call resetIdentity() to recover.
            // Refusing to auto-mint a new device_id prevents provenance loss on
            // server-secret rotation (every queued row would otherwise re-attribute
            // to a freshly-minted device).
            Log.w(TAG, "Sync disabled — skipping enrolment. Call resetIdentity() to recover.")
            return null
        }
        getToken(context)?.let { return it }

        return try {
            val url = URL("$BASE_URL/enroll")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
            }
            try {
                DataOutputStream(conn.outputStream).use { it.writeBytes("{}") }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Enroll failed: ${conn.responseCode} ${conn.responseMessage}")
                    return null
                }
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(body, Map::class.java) as Map<String, String>
                val token = parsed["token"] ?: return null
                val deviceId = parsed["device_id"] ?: return null
                prefs(context).edit()
                    .putString(KEY_TOKEN, token)
                    .putString(KEY_DEVICE_ID, deviceId)
                    .apply()
                Log.d(TAG, "Enrolled: device_id=$deviceId")
                token
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Enroll I/O error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Enroll failed", e)
            null
        }
    }

    /**
     * POST JSON payload to `path` with the bearer token. Returns true on 2xx.
     * Blocking; call from IO thread.
     */
    fun postJson(context: Context, path: String, payload: Map<String, Any?>): Boolean {
        val token = ensureEnrolled(context) ?: return false
        return try {
            val url = URL("$BASE_URL$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
            }
            try {
                val body = gson.toJson(payload)
                DataOutputStream(conn.outputStream).use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                when {
                    code in 200..299 -> true
                    code == 401 -> {
                        // Server rejected our token. Do NOT silently re-enrol:
                        // that would cause queued offline assessments to be re-
                        // attributed to a fresh device_id and break the audit
                        // trail. Instead, disable sync and wait for the user to
                        // take an explicit recovery step (Settings → Reset sync
                        // or Delete my data).
                        Log.w(TAG, "POST $path: 401 — disabling sync until reset")
                        markSyncDisabled(context)
                        false
                    }
                    else -> {
                        Log.w(TAG, "POST $path failed: $code ${conn.responseMessage}")
                        false
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "POST $path I/O error: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed", e)
            false
        }
    }
}
