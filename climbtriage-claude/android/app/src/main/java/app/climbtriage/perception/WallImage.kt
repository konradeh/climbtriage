package app.climbtriage.perception

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure image operations on ARGB `IntArray`s (testable on the JVM, no Bitmap).
 *
 * The median wall and the stillness check are the stationary special case of the reference's
 * mosaic and camera track (vision-demos rock_climbing/src/mosaic.py `build`/`_median`,
 * src/camera.py `choose_reference`; Apache-2.0, commit 6e45309): with a still camera the canvas is
 * the frame, and a per-pixel median over time erases the moving climber.
 */
object WallImage {

    /** Per-pixel, per-channel median of N same-size ARGB frames. */
    fun median(frames: List<IntArray>): IntArray {
        require(frames.isNotEmpty())
        val n = frames[0].size
        require(frames.all { it.size == n }) { "frames must share a size" }
        val out = IntArray(n)
        val r = IntArray(frames.size); val g = IntArray(frames.size); val b = IntArray(frames.size)
        val mid = frames.size / 2
        for (i in 0 until n) {
            for (k in frames.indices) {
                val c = frames[k][i]
                r[k] = (c shr 16) and 0xFF; g[k] = (c shr 8) and 0xFF; b[k] = c and 0xFF
            }
            r.sort(); g.sort(); b.sort()
            out[i] = (0xFF shl 24) or (r[mid] shl 16) or (g[mid] shl 8) or b[mid]
        }
        return out
    }

    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) {
        val c = argb[it]
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
    }

    /** Box-downsample a luma image by an integer factor. */
    fun downsample(src: FloatArray, w: Int, h: Int, factor: Int): Triple<FloatArray, Int, Int> {
        val ow = w / factor; val oh = h / factor
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            var s = 0f
            for (dy in 0 until factor) for (dx in 0 until factor) s += src[(y * factor + dy) * w + x * factor + dx]
            out[y * ow + x] = s / (factor * factor)
        }
        return Triple(out, ow, oh)
    }
}

/**
 * Did the camera move? Block-matching of each sampled frame against a reference, on gradient
 * images (lighting-robust) with the person's box masked out, searching integer shifts.
 *
 * This detects pans/bumps. It does not estimate zoom or rotation; a large residual after the best
 * shift is also reported as movement, so those are caught as "moved" rather than mis-registered.
 */
class StillnessCheck(
    private val maxShiftPx: Int = 6,
    private val movedShiftPx: Double = 1.5,
    private val movedResidualRatio: Double = 1.8,
) {
    data class FrameVerdict(val shiftX: Int, val shiftY: Int, val residual: Double, val moved: Boolean)

    data class Verdict(val frames: List<FrameVerdict>, val maxShiftNorm: Double) {
        val anyMoved: Boolean get() = frames.any { it.moved }
    }

    fun gradient(l: FloatArray, w: Int, h: Int): FloatArray {
        val g = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = l[y * w + x + 1] - l[y * w + x - 1]
            val gy = l[(y + 1) * w + x] - l[(y - 1) * w + x]
            g[y * w + x] = abs(gx) + abs(gy)
        }
        return g
    }

    /** Mean absolute difference of `b` shifted by (sx, sy) against `a`, skipping masked pixels. */
    private fun sad(a: FloatArray, b: FloatArray, w: Int, h: Int, sx: Int, sy: Int, mask: BooleanArray?): Double {
        var s = 0.0; var n = 0
        val m = maxShiftPx + 1
        for (y in m until h - m) for (x in m until w - m) {
            val i = y * w + x
            if (mask != null && mask[i]) continue
            s += abs(a[i] - b[(y + sy) * w + (x + sx)]); n++
        }
        return if (n > 0) s / n else Double.MAX_VALUE
    }

    /**
     * `reference` and `frames` are gradient images of equal size; `masks[i]` (optional) marks
     * pixels to ignore in frame i (the climber). The zero-shift residual of an unmoved frame is the
     * noise floor; a frame counts as moved when its best shift is large or even the best shift
     * leaves a residual well above that floor.
     */
    fun check(reference: FloatArray, frames: List<FloatArray>, w: Int, h: Int, masks: List<BooleanArray?>): Verdict {
        val verdicts = frames.mapIndexed { idx, f ->
            var best = Double.MAX_VALUE; var bx = 0; var by = 0
            for (sy in -maxShiftPx..maxShiftPx) for (sx in -maxShiftPx..maxShiftPx) {
                val s = sad(reference, f, w, h, sx, sy, masks.getOrNull(idx))
                if (s < best - 1e-9 || (abs(s - best) <= 1e-9 && abs(sx) + abs(sy) < abs(bx) + abs(by))) {
                    best = s; bx = sx; by = sy
                }
            }
            Triple(bx, by, best)
        }
        val floor = verdicts.map { it.third }.sorted().let { it[it.size / 2] }.coerceAtLeast(1e-6)
        val out = verdicts.map { (bx, by, r) ->
            val shift = kotlin.math.hypot(bx.toDouble(), by.toDouble())
            FrameVerdict(bx, by, r, shift > movedShiftPx || r > movedResidualRatio * floor)
        }
        val maxShift = out.maxOfOrNull { kotlin.math.hypot(it.shiftX.toDouble(), it.shiftY.toDouble()) } ?: 0.0
        return Verdict(out, maxShift / max(w, h))
    }
}
