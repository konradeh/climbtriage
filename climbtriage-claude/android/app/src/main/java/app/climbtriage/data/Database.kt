package app.climbtriage.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Metadata in Room; dense data (manifest, detections, tracks, raw hold runs) as versioned
 * `climbtriage.v1` JSON files in the session's app-private directory, referenced from here.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val source: String,
    val durationUs: Long,
    val hasDetections: Boolean = false,
    val hasTrack: Boolean = false,
    val holdCount: Int = 0,
    val latestWallVersion: Int = 0,
)

@Entity(tableName = "analysis_runs", indices = [Index("sessionId")])
data class AnalysisRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,
    val createdAt: Long,
    val json: String,            // contracts.AnalysisRun
    val outputFile: String?,     // relative to the session dir
)

@Entity(tableName = "corrections", indices = [Index(value = ["sessionId", "seq"], unique = true)])
data class CorrectionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val seq: Int,
    val json: String,            // contracts.Correction
)

@Entity(tableName = "versions", indices = [Index(value = ["sessionId", "kind", "version"], unique = true)])
data class VersionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,            // wall | route
    val version: Int,
    val json: String,            // contracts.WallVersion / RouteVersion
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun sessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Insert
    suspend fun insertRun(r: AnalysisRunEntity)

    @Query("SELECT * FROM analysis_runs WHERE sessionId = :sessionId AND kind = :kind ORDER BY createdAt")
    suspend fun runs(sessionId: String, kind: String): List<AnalysisRunEntity>

    @Query("DELETE FROM analysis_runs WHERE sessionId = :id")
    suspend fun deleteRuns(id: String)

    @Insert
    suspend fun insertCorrection(c: CorrectionEntity)

    @Query("SELECT * FROM corrections WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun corrections(sessionId: String): List<CorrectionEntity>

    @Query("DELETE FROM corrections WHERE sessionId = :sessionId AND seq > :afterSeq")
    suspend fun dropCorrectionsAfter(sessionId: String, afterSeq: Int)

    @Query("DELETE FROM corrections WHERE sessionId = :id")
    suspend fun deleteCorrections(id: String)

    @Insert
    suspend fun insertVersion(v: VersionEntity)

    @Query("SELECT * FROM versions WHERE sessionId = :sessionId AND kind = :kind ORDER BY version DESC LIMIT 1")
    suspend fun latestVersion(sessionId: String, kind: String): VersionEntity?

    @Query("SELECT * FROM versions WHERE sessionId = :sessionId ORDER BY kind, version")
    suspend fun versions(sessionId: String): List<VersionEntity>

    @Query("DELETE FROM versions WHERE sessionId = :id")
    suspend fun deleteVersions(id: String)
}

@Database(
    entities = [SessionEntity::class, AnalysisRunEntity::class, CorrectionEntity::class, VersionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
}
