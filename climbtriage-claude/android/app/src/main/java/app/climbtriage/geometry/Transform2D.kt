package app.climbtriage.geometry

import kotlin.math.abs
import kotlin.math.min

/** A point in some declared coordinate system. The system is tracked by the caller's types/names. */
data class Pt(val x: Double, val y: Double)

/**
 * A 3×3 projective transform (row-major). Affine transforms are the common case; the
 * projective form is kept so a wall registration (homography) composes with the same type.
 */
class Transform2D(private val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    fun apply(p: Pt): Pt {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        require(abs(w) > 1e-12) { "point maps to infinity" }
        return Pt((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    fun apply(x: Double, y: Double): Pt = apply(Pt(x, y))

    /** `this` first, then [next]. */
    fun then(next: Transform2D): Transform2D = Transform2D(mul(next.m, m))

    fun inverse(): Transform2D {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        require(abs(det) > 1e-15) { "singular transform" }
        val inv = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Transform2D(DoubleArray(9) { inv[it] / det })
    }

    fun values(): DoubleArray = m.copyOf()

    companion object {
        val IDENTITY = Transform2D(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun scale(sx: Double, sy: Double) = Transform2D(doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))
        fun translate(tx: Double, ty: Double) = Transform2D(doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0))
        fun affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) =
            Transform2D(doubleArrayOf(a, b, c, d, e, f, 0.0, 0.0, 1.0))

        private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}

/**
 * The orientation contract between the encoded video and what the user sees.
 *
 * `rotationDeg` is the container's clockwise display rotation (MediaFormat.KEY_ROTATION /
 * METADATA_KEY_VIDEO_ROTATION). `mirrored` flips the displayed frame horizontally.
 */
data class FrameGeometry(val encodedWidth: Int, val encodedHeight: Int, val rotationDeg: Int, val mirrored: Boolean) {
    init {
        require(rotationDeg in setOf(0, 90, 180, 270)) { "rotation must be a multiple of 90, was $rotationDeg" }
    }

    val displayWidth: Int get() = if (rotationDeg % 180 == 0) encodedWidth else encodedHeight
    val displayHeight: Int get() = if (rotationDeg % 180 == 0) encodedHeight else encodedWidth

    /** `video_px` (encoded grid) → `frame_norm` (display-oriented, [0,1]²). */
    fun videoPxToFrameNorm(): Transform2D {
        val w = encodedWidth.toDouble()
        val h = encodedHeight.toDouble()
        val rotate = when (rotationDeg) {
            0 -> Transform2D.IDENTITY
            90 -> Transform2D.affine(0.0, -1.0, h, 1.0, 0.0, 0.0)      // (x,y) → (h−y, x)
            180 -> Transform2D.affine(-1.0, 0.0, w, 0.0, -1.0, h)      // (x,y) → (w−x, h−y)
            270 -> Transform2D.affine(0.0, 1.0, 0.0, -1.0, 0.0, w)     // (x,y) → (y, w−x)
            else -> error("unreachable")
        }
        val dw = displayWidth.toDouble()
        val mirror = if (mirrored) Transform2D.affine(-1.0, 0.0, dw, 0.0, 1.0, 0.0) else Transform2D.IDENTITY
        return rotate.then(mirror).then(Transform2D.scale(1.0 / dw, 1.0 / displayHeight))
    }

    fun frameNormToVideoPx(): Transform2D = videoPxToFrameNorm().inverse()
}

/** Where the content lands inside a view when fitted with letterbox/pillarbox (PlayerView RESIZE_MODE_FIT). */
data class Viewport(val left: Double, val top: Double, val width: Double, val height: Double) {
    /** `frame_norm` → view pixels. */
    fun fromFrameNorm(): Transform2D = Transform2D.scale(width, height).then(Transform2D.translate(left, top))

    fun toFrameNorm(): Transform2D = fromFrameNorm().inverse()

    companion object {
        fun fit(contentWidth: Double, contentHeight: Double, viewWidth: Double, viewHeight: Double): Viewport {
            require(contentWidth > 0 && contentHeight > 0 && viewWidth > 0 && viewHeight > 0)
            val s = min(viewWidth / contentWidth, viewHeight / contentHeight)
            val w = contentWidth * s
            val h = contentHeight * s
            return Viewport((viewWidth - w) / 2.0, (viewHeight - h) / 2.0, w, h)
        }
    }
}
