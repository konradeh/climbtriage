package app.climbtriage.geometry

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** sRGB (D65) → CIELAB. Used for route suggestion and the colour-assist heuristic. */
object ColorLab {
    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0

    fun fromRgb(r: Int, g: Int, b: Int): DoubleArray {
        val rl = lin(r)
        val gl = lin(g)
        val bl = lin(b)
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun fromArgb(argb: Int) = fromRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    fun chroma(lab: DoubleArray) = sqrt(lab[1] * lab[1] + lab[2] * lab[2])

    /**
     * Colour distance for "is this the same hold colour?". Lightness is down-weighted because gym
     * lighting and chalk change L* far more than hue; this is a heuristic, not a perceptual metric.
     */
    fun holdDistance(a: List<Double>, b: List<Double>): Double {
        val dl = 0.5 * (a[0] - b[0])
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
}
