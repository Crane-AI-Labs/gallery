package com.google.ai.edge.gallery.healthdemo.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ─── Saved Assessment Entity ─────────────────────────────────────────────────

@Entity(tableName = "saved_assessments")
data class SavedAssessmentEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val role: PatientRole,
    val customRole: String,
    val symptoms: String,
    val durationValue: String,
    val durationUnit: DurationUnit,
    val age: AgeRange?,
    val sex: Sex?,
    val vitalSigns: VitalSigns,
    val confirmedSigns: Set<String>,
    val guidance: HealthGuidance,
    val clinicianConfirmation: ClinicianConfirmation?,
    val referralInfo: ReferralInfo?,
    // Location — rounded to 2 decimal places for privacy (~1.1km grid)
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val district: String? = null,
    // Sync: ms timestamp of the last successful POST to the Uganda API,
    // or null if never uploaded. Used to skip already-synced rows during
    // startup backfill so we don't re-push everything every launch.
    val syncedAt: Long? = null
)

fun SavedAssessment.toEntity() = SavedAssessmentEntity(
    id = id, timestamp = timestamp, role = role, customRole = customRole,
    symptoms = symptoms, durationValue = durationValue, durationUnit = durationUnit,
    age = age, sex = sex, vitalSigns = vitalSigns, confirmedSigns = confirmedSigns,
    guidance = guidance, clinicianConfirmation = clinicianConfirmation, referralInfo = referralInfo,
    latitude = latitude, longitude = longitude,
    locationAccuracyMeters = locationAccuracyMeters, district = district
)

fun SavedAssessmentEntity.toDomain() = SavedAssessment(
    id = id, timestamp = timestamp, role = role, customRole = customRole,
    symptoms = symptoms, durationValue = durationValue, durationUnit = durationUnit,
    age = age, sex = sex, vitalSigns = vitalSigns, confirmedSigns = confirmedSigns,
    guidance = guidance, clinicianConfirmation = clinicianConfirmation, referralInfo = referralInfo,
    latitude = latitude, longitude = longitude,
    locationAccuracyMeters = locationAccuracyMeters, district = district
)

@Dao
interface AssessmentDao {
    @Query("SELECT * FROM saved_assessments ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SavedAssessmentEntity>>

    @Query("SELECT * FROM saved_assessments WHERE id = :id")
    suspend fun getById(id: String): SavedAssessmentEntity?

    @Query("SELECT * FROM saved_assessments WHERE syncedAt IS NULL ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<SavedAssessmentEntity>

    @Query("UPDATE saved_assessments SET syncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedAssessmentEntity)

    @Update
    suspend fun update(entity: SavedAssessmentEntity)

    @Query("DELETE FROM saved_assessments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM saved_assessments")
    suspend fun deleteAll()
}

// ─── Paused Consultation Entity ──────────────────────────────────────────────

@Entity(tableName = "paused_consultations")
data class PausedConsultationEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val role: PatientRole,
    val customRole: String,
    val symptoms: String,
    val durationValue: String,
    val durationUnit: DurationUnit,
    val age: AgeRange?,
    val sex: Sex?,
    val vitalSigns: VitalSigns,
    val checkedSigns: Set<String>,
    val confirmedSigns: Set<String>,
    val pauseReason: PauseReason?,
    val note: String,
    val syncedAt: Long? = null
)

fun PausedConsultation.toEntity() = PausedConsultationEntity(
    id = id, timestamp = timestamp, role = role, customRole = customRole,
    symptoms = symptoms, durationValue = durationValue, durationUnit = durationUnit,
    age = age, sex = sex, vitalSigns = vitalSigns, checkedSigns = checkedSigns,
    confirmedSigns = confirmedSigns, pauseReason = pauseReason, note = note
)

fun PausedConsultationEntity.toDomain() = PausedConsultation(
    id = id, timestamp = timestamp, role = role, customRole = customRole,
    symptoms = symptoms, durationValue = durationValue, durationUnit = durationUnit,
    age = age, sex = sex, vitalSigns = vitalSigns, checkedSigns = checkedSigns,
    confirmedSigns = confirmedSigns, pauseReason = pauseReason, note = note
)

@Dao
interface PausedConsultationDao {
    @Query("SELECT * FROM paused_consultations ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PausedConsultationEntity>>

    @Query("SELECT * FROM paused_consultations WHERE id = :id")
    suspend fun getById(id: String): PausedConsultationEntity?

    @Query("SELECT * FROM paused_consultations WHERE syncedAt IS NULL ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<PausedConsultationEntity>

    @Query("UPDATE paused_consultations SET syncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PausedConsultationEntity)

    @Query("DELETE FROM paused_consultations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM paused_consultations")
    suspend fun deleteAll()
}
