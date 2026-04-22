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
 * Syncs to the Uganda API (Kampala VM) when online; on app launch,
 * any records saved while offline are pushed automatically.
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
            if (UgandaApiSync.isOnline(context)) {
                if (UgandaApiSync.syncAssessment(context, assessment)) {
                    assessmentDao.markSynced(assessment.id, System.currentTimeMillis())
                }
            }
        }
    }

    fun remove(id: String) {
        _savedAssessments.value = _savedAssessments.value.filter { it.id != id }
    }

    fun updateConfirmation(id: String, confirmation: ClinicianConfirmation, referral: ReferralInfo?) {
        scope.launch {
            val entity = assessmentDao.getById(id) ?: return@launch
            // Confirmation is a server-visible mutation → clear syncedAt so the
            // next backfill re-pushes, then mark synced again on success.
            val updated = entity.copy(
                clinicianConfirmation = confirmation,
                referralInfo = referral,
                syncedAt = null,
            )
            assessmentDao.update(updated)
            Log.d(TAG, "Updated confirmation for $id")
            if (UgandaApiSync.isOnline(context)) {
                if (UgandaApiSync.syncAssessment(context, updated.toDomain())) {
                    assessmentDao.markSynced(id, System.currentTimeMillis())
                }
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
            if (UgandaApiSync.isOnline(context)) {
                if (UgandaApiSync.syncPaused(context, consultation)) {
                    pausedDao.markSynced(consultation.id, System.currentTimeMillis())
                }
            }
        }
    }

    fun removePaused(id: String) {
        scope.launch {
            pausedDao.deleteById(id)
            Log.d(TAG, "Removed paused consultation $id")
            UgandaApiSync.removePausedFromCloud(id, context)
        }
    }

    fun getPausedById(id: String): PausedConsultation? =
        pausedConsultations.value.find { it.id == id }

    fun updateReferral(id: String, referral: ReferralInfo) {
        scope.launch {
            val entity = assessmentDao.getById(id) ?: return@launch
            val updated = entity.copy(referralInfo = referral, syncedAt = null)
            assessmentDao.update(updated)
            Log.d(TAG, "Updated referral for $id")
            if (UgandaApiSync.isOnline(context)) {
                if (UgandaApiSync.syncAssessment(context, updated.toDomain())) {
                    assessmentDao.markSynced(id, System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Push un-synced local records to the Uganda API. Called on app launch
     * to catch anything saved while offline. Rows with a non-null `syncedAt`
     * are skipped, so we don't re-push the entire history on every launch.
     */
    private suspend fun syncAllToCloud() {
        if (!UgandaApiSync.isOnline(context)) {
            Log.d(TAG, "Offline — skipping cloud sync")
            return
        }

        Log.d(TAG, "Syncing un-synced records to Uganda API...")
        UgandaApiSync.syncDeviceDiagnostics(context)

        val pendingAssessments = assessmentDao.getUnsynced()
        val pendingPaused = pausedDao.getUnsynced()

        var assessmentsOk = 0
        for (entity in pendingAssessments) {
            if (UgandaApiSync.syncAssessment(context, entity.toDomain())) {
                assessmentDao.markSynced(entity.id, System.currentTimeMillis())
                assessmentsOk++
            }
        }
        var pausedOk = 0
        for (entity in pendingPaused) {
            if (UgandaApiSync.syncPaused(context, entity.toDomain())) {
                pausedDao.markSynced(entity.id, System.currentTimeMillis())
                pausedOk++
            }
        }

        Log.d(
            TAG,
            "Backfill complete: assessments $assessmentsOk/${pendingAssessments.size}, " +
                "paused $pausedOk/${pendingPaused.size}"
        )
    }

    /**
     * Kick off a sync backfill now. Used by Settings → Reset sync to
     * re-enrol immediately rather than making the user wait for the next
     * save or cold start. Fire-and-forget; progress shows up via the
     * existing "Synced" StateFlow indicators.
     */
    fun triggerBackfill() {
        scope.launch { syncAllToCloud() }
    }

    /**
     * DPPA §7 right-to-erasure action. Tells the server to delete tier_1
     * records for this device and revoke its token, then wipes local Room
     * and clears the device identity so the next sync re-enrols fresh.
     *
     * Returns true only if the server acknowledged erasure. Caller should
     * surface the outcome to the user — silent failure would let them
     * believe their data was gone when it wasn't.
     */
    suspend fun deleteAllMyData(): Boolean {
        val ok = UgandaApiSync.deleteMyData(context)
        // Even on network failure, wipe local — the user asked to be erased
        // on this phone and local storage is the most visible trace. The
        // server-side copy will age out via the retention worker if the
        // erasure request never reaches it.
        assessmentDao.deleteAll()
        pausedDao.deleteAll()
        Log.d(TAG, "Local records wiped (server ack=$ok)")
        return ok
    }
}
