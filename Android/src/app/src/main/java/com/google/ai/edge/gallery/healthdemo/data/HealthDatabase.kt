package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "HealthDatabase"

@Database(
    entities = [SavedAssessmentEntity::class, PausedConsultationEntity::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(HealthTypeConverters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun assessmentDao(): AssessmentDao
    abstract fun pausedDao(): PausedConsultationDao

    companion object {
        @Volatile private var INSTANCE: HealthDatabase? = null

        /**
         * ALL migrations must be defined here. Room will refuse to open the database
         * if a migration path is missing — this is intentional. We never want to
         * silently drop patient data.
         *
         * When adding a new column:
         *   1. Bump the version number in @Database annotation
         *   2. Add a Migration(oldVersion, newVersion) with the ALTER TABLE statement
         *   3. Add the new field to the Entity class with a default value
         *   4. Test the migration on a device that has the old schema
         *
         * Example for version 1 → 2:
         *   val MIGRATION_1_2 = object : Migration(1, 2) {
         *       override fun migrate(db: SupportSQLiteDatabase) {
         *           db.execSQL("ALTER TABLE saved_assessments ADD COLUMN location TEXT NOT NULL DEFAULT ''")
         *       }
         *   }
         */
        /** Migration 1→2: Add location fields to saved_assessments */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN latitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN longitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN locationAccuracyMeters REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN district TEXT DEFAULT NULL")
                Log.d(TAG, "Migration 1→2 complete: added location fields")
            }
        }

        /** Migration 2→3: Add syncedAt column to both tables for per-row sync state. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN syncedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE paused_consultations ADD COLUMN syncedAt INTEGER DEFAULT NULL")
                Log.d(TAG, "Migration 2→3 complete: added syncedAt")
            }
        }

        /** Migration 3→4: persist raw age as entered (ageYears + ageMonths), so
         *  the saved-record view can show the exact age alongside the bucketed
         *  AgeRange. Defaults to "" for existing rows — the AgeRange band they
         *  were saved with remains the source of truth. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN ageYears TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN ageMonths TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE paused_consultations ADD COLUMN ageYears TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE paused_consultations ADD COLUMN ageMonths TEXT NOT NULL DEFAULT ''")
                Log.d(TAG, "Migration 3→4 complete: added ageYears + ageMonths")
            }
        }

        /** Migration 4→5 (Makerere #7): persist sessionStartTime so the Case
         *  Details Session Timeline can render "Started / Completed" times
         *  correctly after app restart. Existing rows get NULL, which the
         *  entity-to-domain mapper falls back to the saved `timestamp`. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_assessments ADD COLUMN sessionStartTime INTEGER DEFAULT NULL")
                Log.d(TAG, "Migration 4→5 complete: added sessionStartTime")
            }
        }

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        )

        fun getInstance(context: Context): HealthDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): HealthDatabase {
            // Load SQLCipher native libraries
            System.loadLibrary("sqlcipher")

            val passphrase = getOrCreatePassphrase(context)

            // Back up the database file before opening, in case migration fails
            backupDatabaseIfExists(context)

            return Room.databaseBuilder(
                context.applicationContext,
                HealthDatabase::class.java,
                "ease_health.db"
            )
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray()))
            .addMigrations(*ALL_MIGRATIONS)
            // No fallbackToDestructiveMigration — patient data must NEVER be silently deleted.
            // If a migration is missing, the app will crash on startup with a clear error
            // telling us exactly which migration is needed. This is the correct behavior
            // for a health app — a crash is better than silent data loss.
            .build()
        }

        /**
         * Create a backup copy of the database before any migration attempt.
         * If migration fails, the backup can be restored manually.
         */
        private fun backupDatabaseIfExists(context: Context) {
            try {
                val dbFile = context.getDatabasePath("ease_health.db")
                if (!dbFile.exists()) return

                // Back up all three Room files: .db, -wal, -shm
                // Missing the WAL file means the backup could be incomplete
                val filesToBackup = listOf(
                    dbFile,
                    java.io.File(dbFile.parent, "ease_health.db-wal"),
                    java.io.File(dbFile.parent, "ease_health.db-shm")
                )
                for (file in filesToBackup) {
                    if (file.exists()) {
                        val backup = java.io.File(file.parent, "${file.name}.backup")
                        file.copyTo(backup, overwrite = true)
                    }
                }
                Log.d(TAG, "Database backup created (including WAL/SHM)")
            } catch (e: Exception) {
                // For a health app, backup failure before migration is serious
                Log.e(TAG, "WARN: Failed to create database backup", e)
            }
        }

        /**
         * Get or create a database encryption passphrase.
         *
         * The passphrase is generated once, encrypted with an AES key in Android Keystore
         * (hardware-backed on most devices), and stored in SharedPreferences.
         * This means:
         * - The passphrase survives app updates
         * - It cannot be extracted without the device's Keystore
         * - It is tied to this app's UID
         */
        private fun getOrCreatePassphrase(context: Context): String {
            val prefs = context.getSharedPreferences("db_security", Context.MODE_PRIVATE)
            val stored = prefs.getString("encrypted_passphrase", null)
            val storedIv = prefs.getString("passphrase_iv", null)

            if (stored != null && storedIv != null) {
                return try {
                    decryptPassphrase(stored, storedIv)
                } catch (e: Exception) {
                    // CRITICAL: Do NOT regenerate the passphrase here.
                    // A new passphrase makes the existing encrypted DB permanently unreadable.
                    // This can happen after OS updates that invalidate Keystore keys,
                    // or on some Samsung/Xiaomi devices with Keystore bugs.
                    // Instead, log the error and try the unencrypted fallback path.
                    Log.e(TAG, "CRITICAL: Cannot decrypt database passphrase. " +
                        "Keystore may have been invalidated. Data is preserved but inaccessible " +
                        "until Keystore is restored.", e)
                    // Fall through to generate a new passphrase ONLY for a fresh database.
                    // The old encrypted database file will remain on disk as a backup.
                    val dbFile = context.getDatabasePath("ease_health.db")
                    if (dbFile.exists()) {
                        // Rename the inaccessible DB so it's not overwritten
                        val preservedFile = java.io.File(dbFile.parent, "ease_health.db.keystore_failure.${System.currentTimeMillis()}")
                        dbFile.renameTo(preservedFile)
                        Log.e(TAG, "Preserved inaccessible database at: ${preservedFile.absolutePath}")
                    }
                    generateAndStorePassphrase(context)
                }
            }

            return generateAndStorePassphrase(context)
        }

        private fun generateAndStorePassphrase(context: Context): String {
            val passphrase = java.util.UUID.randomUUID().toString()
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(passphrase.toByteArray())
            val iv = cipher.iv

            context.getSharedPreferences("db_security", Context.MODE_PRIVATE)
                .edit()
                .putString("encrypted_passphrase", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("passphrase_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()

            Log.d(TAG, "Generated and stored new database passphrase")
            return passphrase
        }

        private fun decryptPassphrase(encryptedB64: String, ivB64: String): String {
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(Base64.decode(encryptedB64, Base64.NO_WRAP))
            return String(decrypted)
        }

        private fun getOrCreateKeystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val alias = "ease_health_db_key"
            val entry = keyStore.getEntry(alias, null)
            if (entry is KeyStore.SecretKeyEntry) {
                return entry.secretKey
            }

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return keyGen.generateKey()
        }
    }
}

/**
 * Gson-based type converters for Room.
 * Handles enums, nested data classes, lists, and sets that Room can't store natively.
 */
class HealthTypeConverters {
    private val gson = Gson()

    @TypeConverter fun fromPatientRole(v: PatientRole?): String? = v?.name
    @TypeConverter fun toPatientRole(v: String?): PatientRole? = v?.let { runCatching { PatientRole.valueOf(it) }.getOrNull() }

    @TypeConverter fun fromDurationUnit(v: DurationUnit?): String? = v?.name
    @TypeConverter fun toDurationUnit(v: String?): DurationUnit? = v?.let { runCatching { DurationUnit.valueOf(it) }.getOrNull() }

    @TypeConverter fun fromAgeRange(v: AgeRange?): String? = v?.name
    @TypeConverter fun toAgeRange(v: String?): AgeRange? = v?.let { runCatching { AgeRange.valueOf(it) }.getOrNull() }

    @TypeConverter fun fromSex(v: Sex?): String? = v?.name
    @TypeConverter fun toSex(v: String?): Sex? = v?.let { runCatching { Sex.valueOf(it) }.getOrNull() }

    @TypeConverter fun fromPauseReason(v: PauseReason?): String? = v?.name
    @TypeConverter fun toPauseReason(v: String?): PauseReason? = v?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() }

    @TypeConverter fun fromVitalSigns(v: VitalSigns): String = gson.toJson(v)
    @TypeConverter fun toVitalSigns(v: String): VitalSigns = try {
        val parsed = gson.fromJson(v, VitalSigns::class.java) ?: VitalSigns()
        // Makerere #2 (2026-04-24): the `bloodLoss` field was renamed to
        // `bloodPressure`. Existing rows still carry a `bloodLoss` JSON key
        // — promote it into bloodPressure so the reading isn't silently
        // dropped. New rows are written as `bloodPressure`.
        if (parsed.bloodPressure.isBlank()) {
            val legacy = try {
                org.json.JSONObject(v).optString("bloodLoss", "")
            } catch (e: Exception) { "" }
            if (legacy.isNotBlank()) parsed.copy(bloodPressure = legacy) else parsed
        } else parsed
    } catch (e: Exception) { VitalSigns() }

    @TypeConverter fun fromGuidance(v: HealthGuidance): String = gson.toJson(v)
    @TypeConverter fun toGuidance(v: String): HealthGuidance = try {
        gson.fromJson(v, HealthGuidance::class.java) ?: HealthGuidance(
            possibleCondition = "Data recovery error",
            suggestedTreatment = listOf("Refer to clinician"),
            recommendedNextSteps = listOf("Assessment data may be corrupted")
        )
    } catch (e: Exception) {
        HealthGuidance(
            possibleCondition = "Data recovery error",
            suggestedTreatment = listOf("Refer to clinician"),
            recommendedNextSteps = listOf("Assessment data may be corrupted")
        )
    }

    @TypeConverter fun fromConfirmation(v: ClinicianConfirmation?): String? = v?.let { gson.toJson(it) }
    @TypeConverter fun toConfirmation(v: String?): ClinicianConfirmation? = v?.let { gson.fromJson(it, ClinicianConfirmation::class.java) }

    @TypeConverter fun fromReferralInfo(v: ReferralInfo?): String? = v?.let { gson.toJson(it) }
    @TypeConverter fun toReferralInfo(v: String?): ReferralInfo? = v?.let { gson.fromJson(it, ReferralInfo::class.java) }

    @TypeConverter fun fromStringSet(v: Set<String>): String = gson.toJson(v.toList())
    @TypeConverter fun toStringSet(v: String): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return (gson.fromJson<List<String>>(v, type) ?: emptyList()).toSet()
    }
}
