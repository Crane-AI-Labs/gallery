package com.google.ai.edge.gallery.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Device introspection helpers used to tune inference parameters for the
 * specific phone the app is running on.
 *
 * Two big problems we work around:
 *  1. Budget Redmi/Tecno/Infinix phones with 2-4 GB RAM crash when n_batch is
 *     too large. We scale n_batch by available RAM.
 *  2. Pixel/Tensor phones have only 1-2 big cores; we let llama.cpp pin threads
 *     to those (in JNI) but we also surface the chipset name for diagnostics.
 */
object DeviceInfo {

    /** Total physical RAM on the device, in megabytes. */
    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /** Available RAM right now, in megabytes. */
    fun availableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    /**
     * Recommended n_batch for this device.
     *
     * Lower batch sizes mean less peak memory during prompt processing,
     * at the cost of slower prefill. The trade-off matters most on devices
     * where the LLM weights already eat most of the RAM budget.
     */
    fun recommendedNBatch(context: Context): Int {
        val totalMb = totalRamMb(context)
        return when {
            totalMb < 3500 -> 256    // 2-3 GB devices: minimal batch to avoid OOM
            totalMb < 5500 -> 512    // 4-5 GB devices: conservative
            totalMb < 8500 -> 1024   // 6-8 GB devices: balanced
            else -> 2048             // 8+ GB flagships: max throughput
        }
    }

    /**
     * Approximate chipset name from system properties.
     * Used for diagnostics — not relied upon for any code path.
     */
    fun chipset(): String {
        val board = Build.BOARD
        val hardware = Build.HARDWARE
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""

        // Prefer SoC info (API 31+) when available
        return if (socModel.isNotBlank() && socModel != "unknown") {
            "$socManufacturer $socModel".trim()
        } else {
            "$board / $hardware"
        }
    }

    /**
     * Highest CPU frequency on the SoC, in kHz. 0 if unreadable.
     * Useful as a rough proxy for "is this a flagship or a budget phone".
     */
    fun maxCpuFreqKhz(): Long {
        var maxFreq = 0L
        for (cpuId in 0..15) {
            val file = File("/sys/devices/system/cpu/cpu$cpuId/cpufreq/cpuinfo_max_freq")
            if (!file.exists()) break
            try {
                val freq = file.readText().trim().toLongOrNull() ?: 0L
                if (freq > maxFreq) maxFreq = freq
            } catch (e: Exception) { /* ignore */ }
        }
        return maxFreq
    }

    /**
     * Number of CPUs the OS reports as online.
     * Note: this may be lower than the SoC's physical core count if some are offline.
     */
    fun onlineCpuCount(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * One-line summary suitable for logcat / Firebase logging.
     * Example: "S24 Ultra | Qualcomm SM8650 | 12288 MB RAM | 8 CPUs | 3300 MHz max | nBatch=2048"
     */
    fun summary(context: Context): String {
        val ramMb = totalRamMb(context)
        val recommendedBatch = recommendedNBatch(context)
        val freqMhz = maxCpuFreqKhz() / 1000
        return "${Build.MODEL} | ${chipset()} | $ramMb MB RAM | ${onlineCpuCount()} CPUs | ${freqMhz} MHz max | nBatch=$recommendedBatch"
    }
}
