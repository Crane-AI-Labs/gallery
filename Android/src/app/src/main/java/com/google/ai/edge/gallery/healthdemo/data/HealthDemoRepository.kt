package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "HealthDemoRepository"

/**
 * Room-backed repository for saved and paused patient assessments.
 * Data survives app close, process death, and device restart.
 * Syncs to Firestore when online. On app launch, any records saved
 * while offline are pushed to Firestore automatically.
 */
class HealthDemoRepository(private val context: Context) {

    private val db = HealthDatabase.getInstance(context)
    private val assessmentDao = db.assessmentDao()
    private val pausedDao = db.pausedDao()

    // SupervisorJob: one failed coroutine doesn't cancel siblings
    // ExceptionHandler: log but don't crash for fire-and-forget operations
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Repository coroutine failed", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    val savedAssessments: StateFlow<List<SavedAssessment>> =
        assessmentDao.getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pausedConsultations: StateFlow<List<PausedConsultation>> =
        pausedDao.getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Sync after Room has emitted its first real data.
        // Wait for the first emission from Room (up to 5 seconds), then sync.
        scope.launch {
            // Wait for Room to actually emit data (or timeout if DB is empty)
            withTimeoutOrNull(5000) {
                assessmentDao.getAll().first()
            }
            syncAllToCloud()
        }
    }

    fun save(assessment: SavedAssessment) {
        scope.launch {
            assessmentDao.insert(assessment.toEntity())
            Log.d(TAG, "Saved assessment ${assessment.id}")
            if (FirestoreSync.isOnline(context)) {
                FirestoreSync.syncAssessment(context, assessment)
            }
        }
    }

    fun updateConfirmation(id: String, confirmation: ClinicianConfirmation, referral: ReferralInfo?) {
        scope.launch {
            val entity = assessmentDao.getById(id) ?: return@launch
            val updated = entity.copy(clinicianConfirmation = confirmation, referralInfo = referral)
            assessmentDao.update(updated)
            Log.d(TAG, "Updated confirmation for $id")
            if (FirestoreSync.isOnline(context)) {
                FirestoreSync.syncAssessment(context, updated.toDomain())
            }
        }
    }

    /** Query Room directly — not the potentially stale StateFlow cache. */
    suspend fun getById(id: String): SavedAssessment? =
        assessmentDao.getById(id)?.toDomain()

    /** Non-suspend version for UI code that can't suspend. Uses StateFlow cache. */
    fun getByIdCached(id: String): SavedAssessment? =
        savedAssessments.value.find { it.id == id }

    fun savePaused(consultation: PausedConsultation) {
        scope.launch {
            pausedDao.insert(consultation.toEntity())
            Log.d(TAG, "Saved paused consultation ${consultation.id}")
            if (FirestoreSync.isOnline(context)) {
                FirestoreSync.syncPaused(context, consultation)
            }
        }
    }

    fun removePaused(id: String) {
        scope.launch {
            pausedDao.deleteById(id)
            Log.d(TAG, "Removed paused consultation $id")
            FirestoreSync.removePausedFromCloud(id)
        }
    }

    fun getPausedById(id: String): PausedConsultation? =
        pausedConsultations.value.find { it.id == id }

    fun updateReferral(id: String, referral: ReferralInfo) {
        scope.launch {
            val entity = assessmentDao.getById(id) ?: return@launch
            val updated = entity.copy(referralInfo = referral)
            assessmentDao.update(updated)
            Log.d(TAG, "Updated referral for $id")
            if (FirestoreSync.isOnline(context)) {
                FirestoreSync.syncAssessment(context, updated.toDomain())
            }
        }
    }

    /**
     * Push all local records to Firestore. Called on app launch to catch
     * any records saved while offline. Firestore merge mode means
     * re-syncing an already-synced record is safe (idempotent).
     */
    private suspend fun syncAllToCloud() {
        if (!FirestoreSync.isOnline(context)) {
            Log.d(TAG, "Offline — skipping cloud sync")
            return
        }

        Log.d(TAG, "Syncing all local records to Firestore...")
        FirestoreSync.syncDeviceDiagnostics(context)

        // Read directly from Room DAO, not the StateFlow cache (which may be stale)
        val allAssessments = assessmentDao.getAll().first().map { it.toDomain() }
        val allPaused = pausedDao.getAll().first().map { it.toDomain() }

        allAssessments.forEach { assessment ->
            FirestoreSync.syncAssessment(context, assessment)
        }

        allPaused.forEach { paused ->
            FirestoreSync.syncPaused(context, paused)
        }

        Log.d(TAG, "Cloud sync complete: ${allAssessments.size} assessments, ${allPaused.size} paused")
    }
}
