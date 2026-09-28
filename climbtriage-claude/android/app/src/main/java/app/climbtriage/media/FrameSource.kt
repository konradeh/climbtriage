package app.climbtriage.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import app.climbtriage.contracts.VideoInfo
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoded frames in `frame_norm` orientation (display-oriented, un-mirrored per the manifest).
 *
 * `getFrameAtIndex` applies the container rotation on the platforms we have seen, but that is an
 * implementation detail, so orientation is checked by comparing dimensions: a 90/270° source whose
 * bitmap still has the encoded aspect is rotated here. (180° cannot be detected by size; the
 * platform's rotation path is the same one that handles 90/270, and the replay check on a real
 * rotated clip is part of the M1 device checklist.)
 */
class FrameSource(file: File, private val video: VideoInfo) : Closeable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
    private val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }

    /** Frame [index] (display order), scaled so its long edge is at most [maxEdge]. */
    fun frame(index: Int, maxEdge: Int = 1280): Bitmap? {
        val raw = runCatching { retriever.getFrameAtIndex(index, params) }.getOrNull() ?: return null
        return normalise(raw, maxEdge)
    }

    private fun normalise(raw: Bitmap, maxEdge: Int): Bitmap {
        val m = Matrix()
        val rotated = video.rotationDeg % 180 != 0
        val stillEncodedShape = (raw.width >= raw.height) == (video.widthPx >= video.heightPx)
        if (rotated && stillEncodedShape && video.widthPx != video.heightPx) m.postRotate(video.rotationDeg.toFloat())
        if (video.mirrored) m.postScale(-1f, 1f)
        val long = max(raw.width, raw.height)
        if (long > maxEdge) {
            val s = maxEdge.toFloat() / long
            m.postScale(s, s)
        }
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    override fun close() = retriever.release()

    companion object {
        fun argb(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

        fun scaled(b: Bitmap, maxEdge: Int): Bitmap {
            val long = max(b.width, b.height)
            if (long <= maxEdge) return b
            val s = maxEdge.toDouble() / long
            return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
        }
    }
}
