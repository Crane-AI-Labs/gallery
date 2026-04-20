/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.app.Application
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.ModelAssetManager
import com.google.ai.edge.gallery.healthdemo.data.UgandaApi
import com.google.ai.edge.gallery.healthdemo.data.UgandaApiSync
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.analytics.AnalyticsSyncWorker
import com.google.ai.edge.gallery.analytics.BatteryAnalytics
import com.google.ai.edge.gallery.analytics.ConnectivitySyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // Extract bundled model assets on first launch (background thread)
    if (!ModelAssetManager.isReady(this)) {
      Log.d(TAG, "First launch — extracting bundled models...")
      CoroutineScope(Dispatchers.IO).launch {
        try {
          ModelAssetManager.extractAll(this@GalleryApplication) { fileName, fileIndex, totalFiles, bytesWritten, totalBytes ->
            val pct = (bytesWritten * 100 / totalBytes).toInt()
            Log.d(TAG, "Extracting models: $fileName (${fileIndex + 1}/$totalFiles) — $pct%")
          }
          Log.d(TAG, "Model extraction complete")
        } catch (e: Exception) {
          Log.e(TAG, "Model extraction failed", e)
        }
      }
    }

    // Enroll with the Uganda ingestion API on first launch (idempotent).
    // Diagnostics and the offline backfill are handled inside
    // HealthDemoRepository.init — triggering them here would double-post.
    CoroutineScope(Dispatchers.IO).launch {
      try {
        if (UgandaApiSync.isOnline(this@GalleryApplication)) {
          UgandaApi.ensureEnrolled(this@GalleryApplication)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Uganda API enroll failed: ${e.message}")
      }
    }

    BatteryAnalytics.logBatteryEvent(this, trigger = "app_launch")
    AnalyticsSyncWorker.schedulePeriodic(this)
    ConnectivitySyncScheduler.register(this)
  }

  private fun isEmulator(): Boolean {
    return (Build.FINGERPRINT.contains("generic")
        || Build.FINGERPRINT.contains("emulator")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for")
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.PRODUCT.contains("sdk")
        || Build.PRODUCT.contains("emulator"))
  }

  companion object {
    private const val TAG = "GalleryApplication"
  }
}
