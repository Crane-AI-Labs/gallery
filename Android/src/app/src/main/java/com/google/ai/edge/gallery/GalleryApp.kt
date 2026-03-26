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

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.healthdemo.ui.navigation.HealthDemoNavGraph
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.ui.theme.ThemeSettings

/** Top level composable representing the main screen of the application. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  // Demo Mode Toggle: Set to true for the health companion demo.
  // Set to false to return to the original Easy Health.
  val isDemoMode = true

  if (isDemoMode) {
    // Force light theme for health demo UI
    ThemeSettings.themeOverride.value = Theme.THEME_LIGHT
    val healthDemoViewModel: HealthDemoViewModel = hiltViewModel()
    HealthDemoNavGraph(viewModel = healthDemoViewModel)
  } else {
    GalleryNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
  }
}
