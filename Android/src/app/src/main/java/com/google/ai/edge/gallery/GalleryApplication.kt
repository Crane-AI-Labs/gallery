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
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.analytics.AnalyticsSyncWorker
import com.google.ai.edge.gallery.analytics.BatteryAnalytics
import com.google.ai.edge.gallery.analytics.ConnectivitySyncScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)

    // On emulators, reduce Firebase's upload interval so events show up faster
    if (isEmulator()) {
      Log.d(TAG, "Emulator detected — setting Firebase analytics to minimal dispatch interval")
      FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
    }

    // Log initial battery level
    BatteryAnalytics.logBatteryEvent(this, trigger = "app_launch")

    // Schedule periodic fallback sync + listen for connectivity to flush immediately
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
