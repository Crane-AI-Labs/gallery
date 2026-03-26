package com.google.ai.edge.gallery.healthdemo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.ui.screens.AssessmentDetailsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.EnterSymptomsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.FeedbackScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.GuidanceScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.HealthDemoLandingScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SavedRecordsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SelectRoleScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SettingsScreen
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

object HealthDemoDestinations {
    const val LANDING = "health_demo_landing"
    const val SETTINGS = "health_demo_settings"
    const val SELECT_ROLE = "health_demo_select_role"
    const val PATIENT_ASSESSMENT = "health_demo_patient_assessment"
    const val GUIDANCE = "health_demo_guidance"
    const val SAVED_RESULTS = "health_demo_saved_results"
    const val ASSESSMENT_DETAILS = "health_demo_assessment_details/{id}"
    const val FEEDBACK = "health_demo_feedback"

    fun assessmentDetails(id: String) = "health_demo_assessment_details/$id"
}

@Composable
fun HealthDemoNavGraph(
    navController: NavHostController = rememberNavController(),
    repository: HealthDemoRepository = remember { HealthDemoRepository() },
    viewModel: HealthDemoViewModel
) {
    val context = LocalContext.current

    // Restore saved role into ViewModel on first composition
    val savedRole = remember {
        val roleName = AppSettings.getRole(context)
        if (roleName != null) {
            PatientRole.entries.find { it.label == roleName }
        } else null
    }
    if (savedRole != null && viewModel.uiState.value.role == null) {
        viewModel.setRole(savedRole)
    }

    NavHost(
        navController = navController,
        startDestination = HealthDemoDestinations.LANDING
    ) {
        // Home screen
        composable(HealthDemoDestinations.LANDING) {
            HealthDemoLandingScreen(
                onStartAssessment = {
                    // Skip role selection if role is already set (from settings or previous selection)
                    if (viewModel.uiState.value.role != null) {
                        navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT)
                    } else {
                        navController.navigate(HealthDemoDestinations.SELECT_ROLE)
                    }
                },
                onSettings = {
                    navController.navigate(HealthDemoDestinations.SETTINGS)
                }
            )
        }

        // Settings
        composable(HealthDemoDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Role selection
        composable(HealthDemoDestinations.SELECT_ROLE) {
            SelectRoleScreen(
                viewModel = viewModel,
                onContinue = {
                    // Save role to persistent settings
                    val role = viewModel.uiState.value.role
                    if (role != null) {
                        AppSettings.saveRole(context, role.label)
                    }
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT)
                }
            )
        }

        // Patient assessment form
        composable(HealthDemoDestinations.PATIENT_ASSESSMENT) {
            EnterSymptomsScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate(HealthDemoDestinations.GUIDANCE)
                },
                onViewSavedResults = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS)
                }
            )
        }

        // Suggested guidance
        composable(HealthDemoDestinations.GUIDANCE) {
            GuidanceScreen(
                viewModel = viewModel,
                repository = repository,
                onCreateNew = {
                    viewModel.clearGuidance()
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                },
                onReturnHome = {
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.LANDING) {
                        popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                    }
                },
                onFeedback = {
                    navController.navigate(HealthDemoDestinations.FEEDBACK) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                }
            )
        }

        // Saved results list
        composable(HealthDemoDestinations.SAVED_RESULTS) {
            SavedRecordsScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() },
                onViewDetails = { id ->
                    navController.navigate(HealthDemoDestinations.assessmentDetails(id))
                }
            )
        }

        // Assessment detail view
        composable(
            route = HealthDemoDestinations.ASSESSMENT_DETAILS,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            AssessmentDetailsScreen(
                assessmentId = id,
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Feedback
        composable(HealthDemoDestinations.FEEDBACK) {
            FeedbackScreen(
                onSubmit = {
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                },
                onSkip = {
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.LANDING) {
                        popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                    }
                }
            )
        }
    }
}
