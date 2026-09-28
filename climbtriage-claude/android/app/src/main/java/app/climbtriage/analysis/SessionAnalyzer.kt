package app.climbtriage.analysis

import android.content.Context
import android.graphics.Bitmap
import app.climbtriage.contracts.AnalysisRun
import app.climbtriage.contracts.Coverage
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.PersonTrack
import app.climbtriage.contracts.Provenance
import app.climbtriage.contracts.Seed
import app.climbtriage.contracts.Stillness
import app.climbtriage.data.SessionRepository
import app.climbtriage.media.FrameSource
import app.climbtriage.media.MediaProbe
import app.climbtriage.perception.DetectionRun
import app.climbtriage.perception.FrameDetections
import app.climbtriage.perception.MediaPipePoseEngine
import app.climbtriage.perception.PersonTracker
import app.climbtriage.perception.StillnessCheck
import app.climbtriage.perception.WallImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Offline analysis of one capture on the device: pose detections at a fixed cadence on real
 * timestamps, a climber-free median wall image, and the stillness verdict. Inference never touches
 * the recording (it reads the finished file), and cancellation is honoured between frames.
 */
class SessionAnalyzer(private val context: Context, private val repo: SessionRepository) {

    data class Progress(val done: Int, val total: Int, val stage: String)

    suspend fun analyze(sessionId: String, cadenceHz: Double = 10.0, onProgress: (Progress) -> Unit) = withContext(Dispatchers.Default) {
        val capture = repo.capture(sessionId)
        val indices = MediaProbe.sampleIndices(capture.timebase.ptsUs, cadenceHz)
        val wallIndices = indices.filterIndexed { i, _ -> i % maxOf(1, indices.size / WALL_SAMPLES) == 0 }.take(WALL_SAMPLES).toSet()
        val started = Instant.now().toString()
        val frames = ArrayList<FrameDetections>(indices.size)
        val wallFrames = ArrayList<Pair<Int, Bitmap>>()
        var dropped = 0

        MediaPipePoseEngine(context).use { engine ->
            FrameSource(repo.mediaFile(sessionId), capture.video).use { source ->
                for ((n, index) in indices.withIndex()) {
                    coroutineContext.ensureActive()
                    val tUs = capture.timebase.ptsUs[index]
                    val bmp = source.frame(index, maxEdge = 1280)
                    if (bmp == null) {
                        dropped++
                        frames += FrameDetections(index, tUs, analyzed = false, people = emptyList())
                    } else {
                        val people = runCatching { engine.detect(bmp) }.getOrNull()
                        if (people == null) dropped++
                        frames += FrameDetections(index, tUs, analyzed = people != null, people = people ?: emptyList())
                        if (index in wallIndices) wallFrames += index to FrameSource.scaled(bmp, WALL_EDGE)
                    }
                    onProgress(Progress(n + 1, indices.size, "pose"))
                }
            }
            onProgress(Progress(indices.size, indices.size, "wall"))
            val runId = Ids.new("run")
            val detections = DetectionRun(runId, engine.model, engine.modelVersion, cadenceHz, 3, frames)
            val run = AnalysisRun(
                id = runId, kind = "pose_detection", inputSha256 = capture.mediaSha256, provider = "on-device",
                model = engine.model, modelVersion = engine.modelVersion, configHash = "cadence=$cadenceHz;maxPeople=3",
                startedAt = started, finishedAt = Instant.now().toString(), status = "succeeded",
                coverage = Coverage(indices.size, indices.size - dropped, dropped),
                provenance = Provenance("model", engine.model, engine.modelVersion, runId),
            )
            repo.saveDetections(sessionId, run, detections)
            buildWallAndStillness(sessionId, wallFrames, frames)
        }
    }

    private suspend fun buildWallAndStillness(sessionId: String, wallFrames: List<Pair<Int, Bitmap>>, frames: List<FrameDetections>) {
        if (wallFrames.size < 3) return
        val w = wallFrames[0].second.width
        val h = wallFrames[0].second.height
        val same = wallFrames.filter { it.second.width == w && it.second.height == h }
        val pixels = same.map { FrameSource.argb(it.second) }
        val median = WallImage.median(pixels)
        val wall = Bitmap.createBitmap(median, w, h, Bitmap.Config.ARGB_8888)
        repo.saveWallImage(sessionId, wall)

        // Stillness on quarter-size gradients, with every detected person masked out.
        val factor = 4
        val check = StillnessCheck()
        val (refL, sw, sh) = WallImage.downsample(WallImage.luma(median), w, h, factor)
        val reference = check.gradient(refL, sw, sh)
        val byIndex = frames.associateBy { it.frameIndex }
        val grads = same.map { (_, bmp) -> WallImage.downsample(WallImage.luma(FrameSource.argb(bmp)), w, h, factor).first.let { check.gradient(it, sw, sh) } }
        val masks = same.map { (index, _) ->
            val people = byIndex[index]?.people ?: emptyList()
            BooleanArray(sw * sh) { i ->
                val x = (i % sw + 0.5) / sw
                val y = (i / sw + 0.5) / sh
                people.any { p -> x >= p.bbox[0] - 0.03 && x <= p.bbox[0] + p.bbox[2] + 0.03 && y >= p.bbox[1] - 0.03 && y <= p.bbox[1] + p.bbox[3] + 0.03 }
            }
        }
        val verdict = check.check(reference, grads, sw, sh, masks)
        val capture = repo.capture(sessionId)
        val ptsOf = { index: Int -> capture.timebase.ptsUs[index] }
        repo.updateCapture(capture.copy(stillness = Stillness(
            verdict = if (verdict.anyMoved) "MOVED" else "STILL",
            maxShiftNorm = verdict.maxShiftNorm,
            movedFrameTimesUs = same.zip(verdict.frames).filter { it.second.moved }.map { ptsOf(it.first.first) },
            method = "blockmatch-gradient.v1 (${same.size} samples)",
        )))
    }

    /** Pure re-association from stored detections — instant, no inference. */
    suspend fun trackFrom(sessionId: String, seedFrame: Int, seedDetection: Int): PersonTrack {
        val detections = repo.detections(sessionId) ?: error("analyse the clip first")
        val tracker = PersonTracker()
        val result = withContext(Dispatchers.Default) { tracker.track(detections.frames, seedFrame, seedDetection) }
        val runId = Ids.new("run")
        val track = PersonTrack(
            id = Ids.new("pt"), captureId = sessionId,
            seed = Seed(detections.frames[seedFrame].tUs, seedDetection),
            samples = result.samples, lostIntervals = result.lostIntervals,
            provenance = Provenance("heuristic", "climbtriage.person-tracker", PersonTracker.Config().version, runId,
                configHash = detections.runId),
        )
        repo.saveTrack(track)
        return track
    }

    companion object {
        const val WALL_SAMPLES = 15
        const val WALL_EDGE = 960
    }
}
