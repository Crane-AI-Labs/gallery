package com.google.ai.edge.gallery.analytics

import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Crash reporting wrapper around Sentry → self-hosted GlitchTip on the
 * Uganda VM. Initialised in [GalleryApplication.onCreate].
 *
 * **Privacy contract** (DPPA 2019):
 *   - Crash payloads ship to GlitchTip on the same Afriqloud VM that holds
 *     the rest of our patient data, NOT to sentry.io. Nothing crosses borders.
 *   - The SDK is configured with `setSendDefaultPii=false` so framework-level
 *     PII (email-style strings auto-detected by Sentry) is stripped before
 *     send.
 *   - Custom keys / breadcrumbs may NEVER carry patient text. Use the
 *     [setCustomKey] wrapper here instead of calling [Sentry.setTag] directly;
 *     the wrapper enforces an allow-list of safe keys (chipset, app version,
 *     triage level, native variant, RAM tier). Any other key is dropped with
 *     a Logcat warning.
 *   - Symptom text, vitals, age, sex, lat/lon, free-text notes — all banned
 *     from breadcrumbs and custom keys by construction.
 */
object CrashReporting {

    private const val TAG = "CrashReporting"

    /** Keys allowed to be attached to crash reports as Sentry tags. Any
     *  other key passed to [setCustomKey] is silently rejected. Curated to
     *  exclude anything patient-derived. */
    private val ALLOWED_TAG_KEYS = setOf(
        "app_version",
        "chipset",
        "native_variant",
        "ram_tier",
        "triage_level",
        "android_api",
        "perf_cores",
    )

    @Volatile private var initialized = false

    fun init(context: Context, dsn: String, environment: String) {
        if (dsn.isBlank()) {
            Log.w(TAG, "Sentry DSN is empty — crash reporting disabled")
            return
        }
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.environment = environment
            // Sample everything — we ship few enough events that 100% is fine.
            options.tracesSampleRate = 0.0       // performance traces off
            options.profilesSampleRate = 0.0
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false   // would capture patient data
            options.isAttachViewHierarchy = false
            options.isEnableAutoSessionTracking = false
            options.isEnableUserInteractionTracing = false
            options.isAnrEnabled = true
            options.isEnableNdk = true            // native crashes from llama_jni
            options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                // Strip free-text "message" content — we don't want any
                // accidental Logcat-mirrored patient strings reaching the
                // server. Keep category + level so the breadcrumb still
                // tells us *what kind* of event happened.
                breadcrumb.message = null
                breadcrumb
            }
        }
        initialized = true
        Log.i(TAG, "Sentry initialised, env=$environment")
    }

    /** Attach a metadata tag to the current scope. Silently rejected unless
     *  the key is in the allow-list — patient-derived data must NEVER leak
     *  via Sentry context. */
    fun setCustomKey(key: String, value: String?) {
        if (!initialized) return
        if (key !in ALLOWED_TAG_KEYS) {
            Log.w(TAG, "Sentry custom key '$key' rejected — not in allow-list")
            return
        }
        Sentry.setTag(key, value ?: "null")
    }

    /** Manual exception capture for caught-but-noteworthy errors. */
    fun captureException(throwable: Throwable, message: String? = null) {
        if (!initialized) return
        if (message != null) {
            Sentry.addBreadcrumb(message)
        }
        Sentry.captureException(throwable)
    }

    /** Force-throw — for verifying the pipeline end-to-end during QA. */
    fun debugThrowTestCrash() {
        throw RuntimeException("CrashReporting.debugThrowTestCrash — verify GlitchTip ingestion")
    }

    /** Warning-level message capture (no exception). Useful for
     *  "shouldn't happen but we recovered" cases. */
    fun captureMessage(message: String, level: SentryLevel = SentryLevel.WARNING) {
        if (!initialized) return
        Sentry.captureMessage(message, level)
    }
}
