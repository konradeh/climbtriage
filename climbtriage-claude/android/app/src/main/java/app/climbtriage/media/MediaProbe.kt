package app.climbtriage.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.climbtriage.contracts.Timebase
import app.climbtriage.contracts.VideoInfo
import java.io.File

/**
 * Reads what the container actually says: every video sample's presentation timestamp, the
 * encoded size and the display rotation. Nothing downstream computes time from a frame count.
 */
object MediaProbe {
    data class Probe(val video: VideoInfo, val timebase: Timebase)

    fun probe(file: File): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track in ${file.name}")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val pts = ArrayList<Long>()
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                pts += t
                if (!extractor.advance()) break
            }
            pts.sort()          // decode order → display order
            val retriever = MediaMetadataRetriever()
            val (w, h, rot, durUs) = try {
                retriever.setDataSource(file.absolutePath)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val dur = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
                listOf(w.toLong(), h.toLong(), rot.toLong(), dur)
            } finally {
                retriever.release()
            }
            val rotation = ((rot.toInt() % 360) + 360) % 360
            val swap = rotation % 180 != 0
            val nominal = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
            } else null
            val duration = if (pts.size >= 2) maxOf(durUs, pts.last() - pts.first()) else durUs
            val video = VideoInfo(
                widthPx = w.toInt(), heightPx = h.toInt(), rotationDeg = rotation, mirrored = false,
                displayWidthPx = if (swap) h.toInt() else w.toInt(), displayHeightPx = if (swap) w.toInt() else h.toInt(),
                durationUs = duration, codec = format.getString(MediaFormat.KEY_MIME), nominalFps = nominal,
            )
            return Probe(video, Timebase(ptsUs = pts))
        } finally {
            extractor.release()
        }
    }

    /**
     * Display-order frame indices sampled at [cadenceHz] on the real timestamps. Targets sit on a
     * fixed time grid (first PTS + k/cadence), so the achieved rate does not drift when the frame
     * interval is not a divisor of the cadence interval, and variable frame rate is handled.
     */
    fun sampleIndices(ptsUs: List<Long>, cadenceHz: Double): List<Int> {
        if (ptsUs.isEmpty()) return emptyList()
        val step = (1e6 / cadenceHz).toLong()
        val out = ArrayList<Int>()
        var target = ptsUs.first()
        for ((i, t) in ptsUs.withIndex()) {
            if (t >= target) {
                out += i
                while (target <= t) target += step
            }
        }
        return out
    }
}
