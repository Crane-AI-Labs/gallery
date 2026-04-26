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

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.services)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  kotlin("kapt")
}

kapt {
  arguments {
    // Room schema export for migration validation
    arg("room.schemaLocation", "$projectDir/schemas")
  }
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35
  ndkVersion = "27.1.12297006"

  defaultConfig {
    applicationId = "com.craneailabs.easehealth"
    minSdk = 31
    targetSdk = 35
    versionCode = 114
    versionName = "1.0.14"

    // Sentry / GlitchTip DSN — points at our self-hosted GlitchTip on the
    // Uganda VM (DPPA §19 data sovereignty). The /_e/ subpath is the nginx
    // proxy prefix (avoids conflict with the FastAPI /api/* routes).
    buildConfigField(
        "String",
        "SENTRY_DSN",
        "\"https://f4c6fd5dfc424d63b469a59bd7413537@41.220.3.234/_e/1\""
    )

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "com.craneailabs.easehealth"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // TODO: Create a proper release keystore before production deployment
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  // Store model files uncompressed — they're already quantized and barely compress.
  // This also prevents the compressAssets task from loading them into memory.
  aaptOptions {
    noCompress += listOf(
      "gguf", "onnx", "json",
      "partaa", "partab", "partac", "partad", "partae"
    )
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.commonmark)
  implementation(libs.onnxruntime.android)
  implementation(libs.richtext)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.perf)
  implementation("com.google.firebase:firebase-firestore-ktx")
  implementation("com.google.firebase:firebase-auth-ktx")
  implementation("com.google.android.gms:play-services-location:21.3.0")
  implementation(libs.androidx.exifinterface)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
  implementation("androidx.sqlite:sqlite-ktx:2.4.0")
  // Crash reporting → self-hosted GlitchTip on the Uganda VM (Sentry-protocol
  // compatible). Stays inside Uganda for DPPA §19 compliance. NDK variant
  // captures native crashes from llama_jni / rnllama.
  implementation(libs.sentry.android)
  implementation(libs.sentry.android.ndk)
  kapt(libs.room.compiler)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
