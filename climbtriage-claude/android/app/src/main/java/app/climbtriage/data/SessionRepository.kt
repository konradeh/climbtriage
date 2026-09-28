package app.climbtriage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import app.climbtriage.contracts.AnalysisRun
import app.climbtriage.contracts.Capture
import app.climbtriage.contracts.ContractJson
import app.climbtriage.contracts.Correction
import app.climbtriage.contracts.DeviceInfo
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.PersonTrack
import app.climbtriage.contracts.RouteRules
import app.climbtriage.contracts.RouteVersion
import app.climbtriage.contracts.SessionDocument
import app.climbtriage.contracts.WallVersion
import app.climbtriage.holds.DisplayNumbering
import app.climbtriage.holds.HoldMap
import app.climbtriage.holds.HoldMapState
import app.climbtriage.media.MediaProbe
import app.climbtriage.perception.DetectionRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Raw hold candidates from one run (backend or heuristic), stored immutably. */
@Serializable
data class HoldRunFile(val run: AnalysisRun, val holds: List<Hold>)

/**
 * The single owner of session storage: app-private files + Room rows. Every write of dense data
 * goes to a file first, then the row that references it, so a crash never leaves a row pointing
 * at nothing. Deletion removes both.
 */
class SessionRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.sessions()
    private val root = File(context.filesDir, "sessions")

    fun sessions(): Flow<List<SessionEntity>> = dao.sessions()
    suspend fun session(id: String) = dao.session(id)

    fun dir(sessionId: String) = File(root, sessionId)
    fun mediaFile(sessionId: String) = File(dir(sessionId), "media.mp4")
    fun wallImageFile(sessionId: String) = File(dir(sessionId), "wall.jpg")
    private fun captureFile(sessionId: String) = File(dir(sessionId), "capture.json")
    private fun detectionsFile(sessionId: String) = File(dir(sessionId), "detections.json")
    private fun trackFile(sessionId: String) = File(dir(sessionId), "track.json")

    /** A fresh session directory for CameraX to record into. */
    fun newRecordingTarget(): Pair<String, File> {
        val id = Ids.new("cap")
        val f = mediaFile(id)
        f.parentFile!!.mkdirs()
        return id to f
    }

    suspend fun importVideo(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = Ids.new("cap")
        val target = mediaFile(id)
        target.parentFile!!.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        register(id, target, source = "imported")
        id
    }

    suspend fun registerRecording(id: String): String = withContext(Dispatchers.IO) {
        register(id, mediaFile(id), source = "recorded")
        id
    }

    private suspend fun register(id: String, media: File, source: String) {
        val probe = try {
            MediaProbe.probe(media)
        } catch (e: Exception) {
            dir(id).deleteRecursively()
            throw e
        }
        val capture = Capture(
            id = id, createdAt = Instant.now().toString(), source = source, mediaSha256 = sha256(media),
            video = probe.video, timebase = probe.timebase,
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
            cameraFacing = if (source == "recorded") "back" else "unknown",
        )
        captureFile(id).writeText(ContractJson.encodeToString(Capture.serializer(), capture))
        dao.upsert(SessionEntity(id, title = if (source == "imported") "Imported clip" else "Recorded clip",
            createdAt = System.currentTimeMillis(), source = source, durationUs = probe.video.durationUs))
    }

    suspend fun capture(id: String): Capture = withContext(Dispatchers.IO) {
        ContractJson.decodeFromString(Capture.serializer(), captureFile(id).readText())
    }

    suspend fun updateCapture(capture: Capture) = withContext(Dispatchers.IO) {
        captureFile(capture.id).writeText(ContractJson.encodeToString(Capture.serializer(), capture))
    }

    // ── perception outputs ────────────────────────────────────────────────
    suspend fun saveDetections(sessionId: String, run: AnalysisRun, detections: DetectionRun) = withContext(Dispatchers.IO) {
        detectionsFile(sessionId).writeText(ContractJson.encodeToString(DetectionRun.serializer(), detections))
        dao.insertRun(AnalysisRunEntity(run.id, sessionId, run.kind, System.currentTimeMillis(),
            ContractJson.encodeToString(AnalysisRun.serializer(), run), detectionsFile(sessionId).name))
        dao.session(sessionId)?.let { dao.upsert(it.copy(hasDetections = true)) }
    }

    suspend fun detections(sessionId: String): DetectionRun? = withContext(Dispatchers.IO) {
        detectionsFile(sessionId).takeIf { it.exists() }?.let { ContractJson.decodeFromString(DetectionRun.serializer(), it.readText()) }
    }

    suspend fun saveWallImage(sessionId: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        wallImageFile(sessionId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    suspend fun wallImage(sessionId: String): Bitmap? = withContext(Dispatchers.IO) {
        wallImageFile(sessionId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    suspend fun saveTrack(track: PersonTrack) = withContext(Dispatchers.IO) {
        trackFile(track.captureId).writeText(ContractJson.encodeToString(PersonTrack.serializer(), track))
        dao.session(track.captureId)?.let { dao.upsert(it.copy(hasTrack = true)) }
    }

    suspend fun track(sessionId: String): PersonTrack? = withContext(Dispatchers.IO) {
        trackFile(sessionId).takeIf { it.exists() }?.let { ContractJson.decodeFromString(PersonTrack.serializer(), it.readText()) }
    }

    // ── holds: raw runs + correction log → versions ───────────────────────
    suspend fun addHoldRun(sessionId: String, run: AnalysisRun, holds: List<Hold>) = withContext(Dispatchers.IO) {
        val name = "holds-${run.id}.json"
        File(dir(sessionId), name).writeText(ContractJson.encodeToString(HoldRunFile.serializer(), HoldRunFile(run, holds)))
        dao.insertRun(AnalysisRunEntity(run.id, sessionId, run.kind, System.currentTimeMillis(),
            ContractJson.encodeToString(AnalysisRun.serializer(), run), name))
    }

    suspend fun rawHolds(sessionId: String): List<Hold> = withContext(Dispatchers.IO) {
        dao.runs(sessionId, "hold_candidates").flatMap { r ->
            val f = File(dir(sessionId), r.outputFile ?: return@flatMap emptyList())
            if (f.exists()) ContractJson.decodeFromString(HoldRunFile.serializer(), f.readText()).holds else emptyList()
        }
    }

    suspend fun runs(sessionId: String, kind: String): List<AnalysisRun> =
        dao.runs(sessionId, kind).map { ContractJson.decodeFromString(AnalysisRun.serializer(), it.json) }

    suspend fun corrections(sessionId: String): List<Correction> =
        dao.corrections(sessionId).map { ContractJson.decodeFromString(Correction.serializer(), it.json) }

    suspend fun appendCorrection(sessionId: String, c: Correction) =
        dao.insertCorrection(CorrectionEntity(c.id, sessionId, c.seq, ContractJson.encodeToString(Correction.serializer(), c)))

    /** Undo is only allowed for corrections not yet captured by a saved version. */
    suspend fun undoLast(sessionId: String): Boolean {
        val saved = latestWall(sessionId)?.correctionSeq ?: 0
        val last = dao.corrections(sessionId).lastOrNull() ?: return false
        if (last.seq <= saved) return false
        dao.dropCorrectionsAfter(sessionId, last.seq - 1)
        return true
    }

    suspend fun nextSeq(sessionId: String) = (dao.corrections(sessionId).lastOrNull()?.seq ?: 0) + 1

    suspend fun holdMap(sessionId: String): HoldMapState = HoldMap.fold(rawHolds(sessionId), corrections(sessionId))

    suspend fun latestWall(sessionId: String): WallVersion? =
        dao.latestVersion(sessionId, "wall")?.let { ContractJson.decodeFromString(WallVersion.serializer(), it.json) }

    suspend fun latestRoute(sessionId: String): RouteVersion? =
        dao.latestVersion(sessionId, "route")?.let { ContractJson.decodeFromString(RouteVersion.serializer(), it.json) }

    /** Snapshot the folded state as the next WallVersion + RouteVersion. Returns the wall version. */
    suspend fun saveVersion(sessionId: String): WallVersion = db.withTransaction {
        val state = holdMap(sessionId)
        val corrections = corrections(sessionId)
        val prevWall = latestWall(sessionId)
        val prevRoute = latestRoute(sessionId)
        val wall = WallVersion(
            id = Ids.new("wv"), wallId = prevWall?.wallId ?: Ids.new("wall"), version = (prevWall?.version ?: 0) + 1,
            parentVersionId = prevWall?.id, reason = if (prevWall == null) "initial" else "correction",
            createdAt = Instant.now().toString(), referenceCaptureId = sessionId, holds = state.holds,
            sourceRunIds = runs(sessionId, "hold_candidates").map { it.id },
            correctionSeq = corrections.lastOrNull()?.seq ?: 0,
        )
        val route = RouteVersion(
            id = Ids.new("rv"), routeId = prevRoute?.routeId ?: Ids.new("route"), version = (prevRoute?.version ?: 0) + 1,
            wallVersionId = wall.id, routeType = state.route.routeType, members = state.route.members.toList(),
            startHoldIds = state.route.start, finishHoldIds = state.route.finish,
            displayNumbers = DisplayNumbering.number(state.holds, state.route.members), rules = RouteRules(),
        )
        dao.insertVersion(VersionEntity(wall.id, sessionId, "wall", wall.version, ContractJson.encodeToString(WallVersion.serializer(), wall)))
        dao.insertVersion(VersionEntity(route.id, sessionId, "route", route.version, ContractJson.encodeToString(RouteVersion.serializer(), route)))
        dao.session(sessionId)?.let { dao.upsert(it.copy(holdCount = state.live.size, latestWallVersion = wall.version)) }
        wall
    }

    /** The whole session as one `climbtriage.v1` document (export / evaluation input). */
    suspend fun document(sessionId: String): SessionDocument {
        val versions = dao.versions(sessionId)
        return SessionDocument(
            capture = capture(sessionId),
            wallVersions = versions.filter { it.kind == "wall" }.map { ContractJson.decodeFromString(WallVersion.serializer(), it.json) },
            routeVersions = versions.filter { it.kind == "route" }.map { ContractJson.decodeFromString(RouteVersion.serializer(), it.json) },
            personTracks = listOfNotNull(track(sessionId)),
            analysisRuns = listOf("pose_detection", "hold_candidates", "tracking", "stillness").flatMap { runs(sessionId, it) },
            corrections = corrections(sessionId),
        )
    }

    suspend fun exportDocument(sessionId: String): File = withContext(Dispatchers.IO) {
        val f = File(dir(sessionId), "session.climbtriage.v1.json")
        f.writeText(ContractJson.encodeToString(SessionDocument.serializer(), document(sessionId)))
        f
    }

    suspend fun delete(sessionId: String) {
        db.withTransaction {
            dao.deleteCorrections(sessionId); dao.deleteVersions(sessionId); dao.deleteRuns(sessionId); dao.deleteSession(sessionId)
        }
        withContext(Dispatchers.IO) { dir(sessionId).deleteRecursively() }
    }

    suspend fun addRunRecord(sessionId: String, run: AnalysisRun) =
        dao.insertRun(AnalysisRunEntity(run.id, sessionId, run.kind, System.currentTimeMillis(),
            ContractJson.encodeToString(AnalysisRun.serializer(), run), null))

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
