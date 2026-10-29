package com.amihay.drumtrainer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.amihay.drumtrainer.model.RunRecord
import kotlinx.coroutines.flow.Flow

/** One round (a "סבב"). */
@Entity
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    /** Exercise kind ("rudiment", "groove"…) or "gap", plus "-together" / "-echo". */
    val mode: String,
    val bpm: Int,
    val startedAt: Long,
    val bars: Int,
    val expected: Int,
    val hits: Int,
    val misses: Int,
    val extras: Int,
    val meanMs: Double,
    val stdMs: Double,
    val hitPct: Double,
    val accentDb: Double?,
    /** 1..5 as rated by the player before seeing the numbers; null if skipped. */
    val selfRating: Int?,
    val passed: Boolean,
    /** Mode-specific extras (e.g. gap-metronome tempo), as JSON. */
    val detailsJson: String? = null,
    /** Training session this round was part of, if any. */
    val sessionId: Long? = null,
)

/** A training session (stage 4): a five-part plan played in one sitting. */
@Entity
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val plannedMinutes: Int,
)

/** One expected note or stray hit within a round. */
@Entity(indices = [Index("runId")])
data class HitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    /** Index of the expected note, or null for an extra hit. */
    val noteIndex: Int?,
    /** "R", "L" or "-". */
    val hand: String,
    val accent: Boolean,
    val deviationMs: Double?,
    val strength: Float?,
    /** Drum of the note, or the drum heard for a stray hit; null for single-pad exercises. */
    val pad: String? = null,
)

@Entity
data class ProgressEntity(
    @PrimaryKey val exerciseId: String,
    val currentBpm: Int,
    val consecutivePasses: Int,
    val bestPassedBpm: Int,
)

data class RunWithName(
    val id: Long,
    val exerciseId: String,
    val mode: String,
    val bpm: Int,
    val startedAt: Long,
    val stdMs: Double,
    val meanMs: Double,
    val hitPct: Double,
    val passed: Boolean,
    val selfRating: Int?,
)

@Dao
interface TrainingDao {
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Insert
    suspend fun insertHits(hits: List<HitEntity>)

    @Query("SELECT id, exerciseId, mode, bpm, startedAt, stdMs, meanMs, hitPct, passed, selfRating FROM RunEntity ORDER BY startedAt DESC LIMIT :limit")
    fun recentRuns(limit: Int): Flow<List<RunWithName>>

    @Query("SELECT exerciseId, bpm, passed, startedAt, sessionId, stdMs FROM RunEntity ORDER BY startedAt")
    suspend fun runRecords(): List<RunRecord>

    @Query("SELECT exerciseId, bpm, passed, startedAt, sessionId, stdMs FROM RunEntity ORDER BY startedAt")
    fun runRecordsFlow(): Flow<List<RunRecord>>

    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Query("UPDATE SessionEntity SET endedAt = :endedAt WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long)

    @Query("SELECT * FROM ProgressEntity")
    fun allProgress(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM ProgressEntity WHERE exerciseId = :id")
    suspend fun progress(id: String): ProgressEntity?

    @Upsert
    suspend fun saveProgress(p: ProgressEntity)
}

@Database(
    entities = [RunEntity::class, HitEntity::class, ProgressEntity::class, SessionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Stage 3 adds the drum of each hit. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE HitEntity ADD COLUMN pad TEXT")
            }
        }

        /** Stage 4 adds training sessions. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RunEntity ADD COLUMN sessionId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS SessionEntity (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, endedAt INTEGER, plannedMinutes INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "training.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
