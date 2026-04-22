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
import com.google.ai.edge.gallery.healthdemo.ui.screens.CaseSavedScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ClinicianConfirmationScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ConsentScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ConsultationSavedScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.EnterSymptomsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.GeneratingAssessmentScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.FeedbackScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.GuidanceScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.HealthDemoLandingScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SavedRecordsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SettingsScreen
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

object HealthDemoDestinations {
    const val CONSENT = "health_demo_consent"
    const val LANDING = "health_demo_landing"
    const val SETTINGS = "health_demo_settings"
    const val PATIENT_ASSESSMENT = "health_demo_patient_assessment"
    const val GENERATING_ASSESSMENT = "health_demo_generating"
    const val GUIDANCE = "health_demo_guidance"
    const val CLINICIAN_CONFIRMATION = "health_demo_clinician_confirmation"
    const val CASE_SAVED = "health_demo_case_saved/{caseId}"
    const val CONSULTATION_SAVED = "health_demo_consultation_saved"
    const val SAVED_RESULTS = "health_demo_saved_results"
    const val ASSESSMENT_DETAILS = "health_demo_assessment_details/{id}"
    const val FEEDBACK = "health_demo_feedback"

    fun caseSaved(id: String) = "health_demo_case_saved/$id"
    fun assessmentDetails(id: String) = "health_demo_assessment_details/$id"
}

@Composable
fun HealthDemoNavGraph(
    navController: NavHostController = rememberNavController(),
    viewModel: HealthDemoViewModel
) {
    val context = LocalContext.current
    val repository = remember { HealthDemoRepository(context.applicationContext) }

    // Restore saved role on launch
    val savedRole = remember {
        val roleName = AppSettings.getRole(context)
        if (roleName != null) PatientRole.entries.find { it.label == roleName } else null
    }
    if (savedRole != null && viewModel.uiState.value.role == null) {
        viewModel.setRole(savedRole)
    }

    val startDestination = if (AppSettings.hasAcceptedConsent(context)) {
        HealthDemoDestinations.LANDING
    } else {
        HealthDemoDestinations.CONSENT
    }

    NavHost(navController = navController, startDestination = startDestination) {

        // ── Consent (DPPA §9 + §27, first launch only) ────────────────────────
        composable(HealthDemoDestinations.CONSENT) {
            ConsentScreen(onAccepted = {
                navController.navigate(HealthDemoDestinations.LANDING) {
                    popUpTo(HealthDemoDestinations.CONSENT) { inclusive = true }
                }
            })
        }

        // ── Landing ───────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.LANDING) {
            HealthDemoLandingScreen(
                repository = repository,
                viewModel = viewModel,
                onStartAssessment = {
                    AppSettings.touchLastActive(context)
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT)
                },
                onViewHistory = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS)
                },
                onSettings = {
                    navController.navigate(HealthDemoDestinations.SETTINGS)
                }
            )
        }

        // ── Settings ──────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                repository = repository,
            )
        }

        // ── Patient Assessment ────────────────────────────────────────────────
        composable(HealthDemoDestinations.PATIENT_ASSESSMENT) {
            EnterSymptomsScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate(HealthDemoDestinations.GENERATING_ASSESSMENT)
                },
                onViewSavedResults = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS)
                }
            )
        }

        // ── Generating Assessment ─────────────────────────────────────────────
        composable(HealthDemoDestinations.GENERATING_ASSESSMENT) {
            GeneratingAssessmentScreen(
                viewModel = viewModel,
                onReady = {
                    navController.navigate(HealthDemoDestinations.GUIDANCE) {
                        popUpTo(HealthDemoDestinations.GENERATING_ASSESSMENT) { inclusive = true }
                    }
                }
            )
        }

        // ── Guidance ──────────────────────────────────────────────────────────
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
                },
                onConfirmOutcome = {
                    navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION)
                },
                onReferralSaved = { savedId ->
                    navController.navigate(HealthDemoDestinations.caseSaved(savedId)) {
                        popUpTo(HealthDemoDestinations.GUIDANCE)
                    }
                },
                onSavePausedAndGoHome = { paused ->
                    // BUG-03: remove auto-saved assessment before saving as paused
                    val autoSavedId = viewModel.uiState.value.savedAssessment?.id
                    if (autoSavedId != null) repository.remove(autoSavedId)
                    repository.savePaused(paused)
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.LANDING) {
                        popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                    }
                }
            )
        }

        // ── Clinician Confirmation ────────────────────────────────────────────
        composable(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
            ClinicianConfirmationScreen(
                viewModel = viewModel,
                repository = repository,
                onCaseSaved = { savedId ->
                    navController.navigate(HealthDemoDestinations.caseSaved(savedId)) {
                        popUpTo(HealthDemoDestinations.GUIDANCE)
                    }
                }
            )
        }

        // ── Case Saved ────────────────────────────────────────────────────────
        composable(
            route = HealthDemoDestinations.CASE_SAVED,
            arguments = listOf(navArgument("caseId") { type = NavType.StringType })
        ) { backStackEntry ->
            val caseId = backStackEntry.arguments?.getString("caseId") ?: ""
            CaseSavedScreen(
                caseId = caseId,
                onContinue = {
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.CONSULTATION_SAVED) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                }
            )
        }

        // ── Consultation Saved ────────────────────────────────────────────────
        composable(HealthDemoDestinations.CONSULTATION_SAVED) {
            ConsultationSavedScreen(
                repository = repository,
                onStartNew = {
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                },
                onViewHistory = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                }
            )
        }

        // ── Saved Results ─────────────────────────────────────────────────────
        composable(HealthDemoDestinations.SAVED_RESULTS) {
            SavedRecordsScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() },
                onResumePaused = { id ->
                    val paused = repository.getPausedById(id) ?: return@SavedRecordsScreen
                    viewModel.loadPausedConsultation(paused)
                    repository.removePaused(id)
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                }
            )
        }

        // ── Assessment Details (fallback direct navigation) ───────────────────
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

        // ── Feedback ──────────────────────────────────────────────────────────
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
