package com.google.ai.edge.gallery.analytics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.firebaseAnalytics

object BatteryAnalytics {

    data class BatterySnapshot(
        val levelPercent: Int,
        val isCharging: Boolean,
        val temperature: Float,
    )

    fun capture(context: Context): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempCelsius = if (tempRaw > 0) tempRaw / 10f else -1f

        return BatterySnapshot(
            levelPercent = percent,
            isCharging = isCharging,
            temperature = tempCelsius,
        )
    }

    fun logBatteryEvent(context: Context, trigger: String) {
        val snapshot = capture(context) ?: return
        firebaseAnalytics?.logEvent("battery_status", bundleOf(
            "battery_percent" to snapshot.levelPercent,
            "is_charging" to snapshot.isCharging.toString(),
            "battery_temp_c" to snapshot.temperature.toDouble(),
            "trigger" to trigger,
        ))
    }
}
