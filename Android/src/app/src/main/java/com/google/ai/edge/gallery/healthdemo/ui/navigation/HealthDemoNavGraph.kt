package com.google.ai.edge.gallery.healthdemo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.FinalAction
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.ui.screens.AssessmentDetailsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ClinicianConfirmationScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.DangerSignAlertScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.EnterSymptomsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.FeedbackScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.GuidanceScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.HealthDemoLandingScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.PauseConsultationScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ReferralScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.ResumeConsultationScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SavedRecordsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SelectRoleScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.SettingsScreen
import com.google.ai.edge.gallery.healthdemo.ui.screens.WarningSignAlertScreen
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

object HealthDemoDestinations {
    const val LANDING = "health_demo_landing"
    const val SETTINGS = "health_demo_settings"
    const val SELECT_ROLE = "health_demo_select_role"
    const val PATIENT_ASSESSMENT = "health_demo_patient_assessment"
    const val GUIDANCE = "health_demo_guidance"
    const val DANGER_SIGN_ALERT = "health_demo_danger_sign_alert"
    const val WARNING_SIGN_ALERT = "health_demo_warning_sign_alert"
    const val CLINICIAN_CONFIRMATION = "health_demo_clinician_confirmation"
    const val REFERRAL = "health_demo_referral"
    const val PAUSE_CONSULTATION = "health_demo_pause_consultation"
    const val RESUME_CONSULTATION = "health_demo_resume_consultation/{pausedId}"
    const val SAVED_RESULTS = "health_demo_saved_results"
    const val ASSESSMENT_DETAILS = "health_demo_assessment_details/{id}"
    const val FEEDBACK = "health_demo_feedback"

    fun assessmentDetails(id: String) = "health_demo_assessment_details/$id"
    fun resumeConsultation(id: String) = "health_demo_resume_consultation/$id"
}

@Composable
fun HealthDemoNavGraph(
    navController: NavHostController = rememberNavController(),
    repository: HealthDemoRepository = remember { HealthDemoRepository() },
    viewModel: HealthDemoViewModel
) {
    val context = LocalContext.current

    val savedRole = remember {
        val roleName = AppSettings.getRole(context)
        if (roleName != null) PatientRole.entries.find { it.label == roleName } else null
    }
    if (savedRole != null && viewModel.uiState.value.role == null) {
        viewModel.setRole(savedRole)
    }

    NavHost(
        navController = navController,
        startDestination = HealthDemoDestinations.LANDING
    ) {

        // ── Landing ───────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.LANDING) {
            HealthDemoLandingScreen(
                repository = repository,
                onStartAssessment = {
                    if (viewModel.uiState.value.role != null) {
                        navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT)
                    } else {
                        navController.navigate(HealthDemoDestinations.SELECT_ROLE)
                    }
                },
                onViewHistory = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS)
                },
                onResumePaused = { id ->
                    navController.navigate(HealthDemoDestinations.resumeConsultation(id))
                },
                onDiscardPaused = { id ->
                    repository.removePaused(id)
                },
                onSettings = {
                    navController.navigate(HealthDemoDestinations.SETTINGS)
                }
            )
        }

        // ── Settings ──────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // ── Role selection ────────────────────────────────────────────────────
        composable(HealthDemoDestinations.SELECT_ROLE) {
            SelectRoleScreen(
                viewModel = viewModel,
                onContinue = {
                    val role = viewModel.uiState.value.role
                    if (role != null) AppSettings.saveRole(context, role.label)
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT)
                }
            )
        }

        // ── Patient assessment ────────────────────────────────────────────────
        composable(HealthDemoDestinations.PATIENT_ASSESSMENT) {
            EnterSymptomsScreen(
                viewModel = viewModel,
                onContinue = {
                    navController.navigate(HealthDemoDestinations.GUIDANCE)
                },
                onViewSavedResults = {
                    navController.navigate(HealthDemoDestinations.SAVED_RESULTS)
                },
                onPause = {
                    navController.navigate(HealthDemoDestinations.PAUSE_CONSULTATION)
                }
            )
        }

        // ── Pause consultation ────────────────────────────────────────────────
        composable(HealthDemoDestinations.PAUSE_CONSULTATION) {
            PauseConsultationScreen(
                viewModel = viewModel,
                onSaveAndStartNew = { paused ->
                    repository.savePaused(paused)
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.LANDING) {
                        popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                    }
                },
                onContinue = {
                    navController.popBackStack()
                }
            )
        }

        // ── Resume consultation ───────────────────────────────────────────────
        composable(
            route = HealthDemoDestinations.RESUME_CONSULTATION,
            arguments = listOf(navArgument("pausedId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pausedId = backStackEntry.arguments?.getString("pausedId") ?: return@composable
            val paused = repository.getPausedById(pausedId) ?: return@composable
            ResumeConsultationScreen(
                paused = paused,
                onResume = {
                    viewModel.loadPausedConsultation(paused)
                    repository.removePaused(pausedId)
                    navController.navigate(HealthDemoDestinations.PATIENT_ASSESSMENT) {
                        popUpTo(HealthDemoDestinations.LANDING)
                    }
                },
                onDiscard = {
                    repository.removePaused(pausedId)
                    navController.popBackStack()
                }
            )
        }

        // ── Guidance ──────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.GUIDANCE) {
            val uiState by viewModel.uiState.collectAsState()
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
                    val redFlags = uiState.guidance?.redFlags ?: emptyList()
                    if (redFlags.isNotEmpty() && uiState.dangerSignIndex < redFlags.size) {
                        navController.navigate(HealthDemoDestinations.DANGER_SIGN_ALERT)
                    } else {
                        navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION)
                    }
                }
            )
        }

        // ── Danger sign alert ─────────────────────────────────────────────────
        composable(HealthDemoDestinations.DANGER_SIGN_ALERT) {
            val uiState by viewModel.uiState.collectAsState()
            val redFlags = uiState.guidance?.redFlags ?: emptyList()
            val idx = uiState.dangerSignIndex

            if (idx >= redFlags.size) {
                // All signs reviewed — go to confirmation
                navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
                    popUpTo(HealthDemoDestinations.GUIDANCE)
                }
                return@composable
            }

            val sign = redFlags[idx]
            // Show danger signs (first half) as danger, rest as warnings
            val isDanger = idx < (redFlags.size + 1) / 2

            fun goNextOrConfirm(nextIndex: Int) {
                if (nextIndex >= redFlags.size) {
                    navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
                        popUpTo(HealthDemoDestinations.GUIDANCE)
                    }
                }
                // else: recompose will show the next sign automatically (idx updated in VM)
            }

            if (isDanger) {
                DangerSignAlertScreen(
                    sign = sign,
                    currentIndex = idx,
                    total = redFlags.size,
                    onPresent = { viewModel.confirmDangerSign(sign); goNextOrConfirm(idx + 1) },
                    onNotPresent = { viewModel.dismissDangerSign(); goNextOrConfirm(idx + 1) },
                    onDismiss = {
                        navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
                            popUpTo(HealthDemoDestinations.GUIDANCE)
                        }
                    }
                )
            } else {
                WarningSignAlertScreen(
                    sign = sign,
                    currentIndex = idx,
                    total = redFlags.size,
                    onPresent = { viewModel.confirmDangerSign(sign); goNextOrConfirm(idx + 1) },
                    onNotPresent = { viewModel.dismissDangerSign(); goNextOrConfirm(idx + 1) },
                    onDismiss = {
                        navController.navigate(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
                            popUpTo(HealthDemoDestinations.GUIDANCE)
                        }
                    }
                )
            }
        }

        // ── Clinician confirmation ────────────────────────────────────────────
        composable(HealthDemoDestinations.CLINICIAN_CONFIRMATION) {
            ClinicianConfirmationScreen(
                onSave = { confirmation ->
                    viewModel.setClinicianConfirmation(confirmation)
                    val savedId = viewModel.uiState.value.savedAssessment?.id
                    if (savedId != null) {
                        if (confirmation.finalAction == FinalAction.Referred) {
                            navController.navigate(HealthDemoDestinations.REFERRAL)
                        } else {
                            repository.updateConfirmation(savedId, confirmation, null)
                            viewModel.resetAssessment()
                            navController.navigate(HealthDemoDestinations.LANDING) {
                                popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        // ── Referral ──────────────────────────────────────────────────────────
        composable(HealthDemoDestinations.REFERRAL) {
            val uiState by viewModel.uiState.collectAsState()
            ReferralScreen(
                onSave = { referral ->
                    viewModel.setReferralInfo(referral)
                    val savedId = uiState.savedAssessment?.id
                    val confirmation = uiState.clinicianConfirmation
                    if (savedId != null && confirmation != null) {
                        repository.updateConfirmation(savedId, confirmation, referral)
                    }
                    viewModel.resetAssessment()
                    navController.navigate(HealthDemoDestinations.LANDING) {
                        popUpTo(HealthDemoDestinations.LANDING) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        // ── Saved results ─────────────────────────────────────────────────────
        composable(HealthDemoDestinations.SAVED_RESULTS) {
            SavedRecordsScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() },
                onViewDetails = { id ->
                    navController.navigate(HealthDemoDestinations.assessmentDetails(id))
                },
                onResumePaused = { id ->
                    navController.navigate(HealthDemoDestinations.resumeConsultation(id))
                }
            )
        }

        // ── Assessment detail ─────────────────────────────────────────────────
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
