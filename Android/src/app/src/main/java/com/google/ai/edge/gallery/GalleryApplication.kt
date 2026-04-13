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
import com.google.ai.edge.gallery.healthdemo.data.FirestoreSync
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.analytics.AnalyticsSyncWorker
import com.google.ai.edge.gallery.analytics.BatteryAnalytics
import com.google.ai.edge.gallery.analytics.ConnectivitySyncScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
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

    // Firebase disabled — no valid google-services.json
    try {
      FirebaseApp.initializeApp(this)
      if (isEmulator()) {
        Log.d(TAG, "Emulator detected — setting Firebase analytics to minimal dispatch interval")
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
      }
      // Anonymous auth — each device gets a unique UID for Firestore security
      val auth = FirebaseAuth.getInstance()
      if (auth.currentUser == null) {
        auth.signInAnonymously().addOnSuccessListener {
          Log.d(TAG, "Anonymous auth: uid=${it.user?.uid}")
        }.addOnFailureListener {
          Log.w(TAG, "Anonymous auth failed: ${it.message}")
        }
      } else {
        Log.d(TAG, "Already authenticated: uid=${auth.currentUser?.uid}")
      }

      BatteryAnalytics.logBatteryEvent(this, trigger = "app_launch")
      AnalyticsSyncWorker.schedulePeriodic(this)
      ConnectivitySyncScheduler.register(this)
    } catch (e: Exception) {
      Log.w(TAG, "Firebase init skipped (no valid config): ${e.message}")
    }
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
