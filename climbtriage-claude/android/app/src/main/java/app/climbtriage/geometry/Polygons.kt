package app.climbtriage.geometry

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Polygon utilities on normalised coordinates. Polygons are closed implicitly (last→first). */
object Polygons {
    fun area(p: List<Pt>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }

    fun centroid(p: List<Pt>): Pt {
        var cx = 0.0
        var cy = 0.0
        var a2 = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val cross = a.x * b.y - b.x * a.y
            a2 += cross
            cx += (a.x + b.x) * cross
            cy += (a.y + b.y) * cross
        }
        if (abs(a2) < 1e-15) return Pt(p.sumOf { it.x } / p.size, p.sumOf { it.y } / p.size)
        return Pt(cx / (3.0 * a2), cy / (3.0 * a2))
    }

    /** [x, y, w, h]. */
    fun bbox(p: List<Pt>): List<Double> {
        val x0 = p.minOf { it.x }
        val y0 = p.minOf { it.y }
        return listOf(x0, y0, p.maxOf { it.x } - x0, p.maxOf { it.y } - y0)
    }

    fun contains(p: List<Pt>, q: Pt): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]
            val b = p[j]
            if ((a.y > q.y) != (b.y > q.y) && q.x < (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance from q to the polygon boundary; 0 inside. */
    fun distance(p: List<Pt>, q: Pt): Double {
        if (contains(p, q)) return 0.0
        var best = Double.MAX_VALUE
        for (i in p.indices) best = min(best, segmentDistance(q, p[i], p[(i + 1) % p.size]))
        return best
    }

    private fun segmentDistance(q: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(q.x - (a.x + t * dx), q.y - (a.y + t * dy))
    }

    /**
     * Clip to the half-plane on the left of the directed line a→b (Sutherland–Hodgman against one
     * edge). Works for non-convex input; a concave polygon cut into several pieces comes back as one
     * ring joined along the line, which is acceptable for splitting a hold outline.
     */
    fun clipLeft(p: List<Pt>, a: Pt, b: Pt): List<Pt> {
        fun side(q: Pt) = (b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x)
        val out = ArrayList<Pt>()
        for (i in p.indices) {
            val cur = p[i]
            val prev = p[(i + p.size - 1) % p.size]
            val sc = side(cur)
            val sp = side(prev)
            if (sc >= 0) {
                if (sp < 0) out += intersect(prev, cur, sp, sc)
                out += cur
            } else if (sp >= 0) {
                out += intersect(prev, cur, sp, sc)
            }
        }
        return out
    }

    private fun intersect(p: Pt, q: Pt, sp: Double, sq: Double): Pt {
        val t = sp / (sp - sq)
        return Pt(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y))
    }

    /** Split along the infinite line through a and b. Returns null if the line does not cut it. */
    fun split(p: List<Pt>, a: Pt, b: Pt, minAreaFraction: Double = 0.02): Pair<List<Pt>, List<Pt>>? {
        val left = clipLeft(p, a, b)
        val right = clipLeft(p, b, a)
        val total = area(p)
        if (left.size < 3 || right.size < 3) return null
        if (area(left) < minAreaFraction * total || area(right) < minAreaFraction * total) return null
        return left to right
    }

    /**
     * Merge as the convex hull of both outlines. A true polygon union is not needed for the M1
     * use (two detections of one hold); the hull is labelled as such in the correction payload.
     */
    fun mergeHull(a: List<Pt>, b: List<Pt>): List<Pt> = convexHull(a + b)

    fun convexHull(points: List<Pt>): List<Pt> {
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Pt>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    fun translate(p: List<Pt>, dx: Double, dy: Double) = p.map { Pt(it.x + dx, it.y + dy) }

    fun scaleAbout(p: List<Pt>, c: Pt, s: Double) = p.map { Pt(c.x + (it.x - c.x) * s, c.y + (it.y - c.y) * s) }

    fun clampUnit(p: List<Pt>) = p.map { Pt(it.x.coerceIn(0.0, 1.0), it.y.coerceIn(0.0, 1.0)) }

    fun box(cx: Double, cy: Double, halfW: Double, halfH: Double) = listOf(
        Pt(max(0.0, cx - halfW), max(0.0, cy - halfH)), Pt(min(1.0, cx + halfW), max(0.0, cy - halfH)),
        Pt(min(1.0, cx + halfW), min(1.0, cy + halfH)), Pt(max(0.0, cx - halfW), min(1.0, cy + halfH)),
    )

    fun fromLists(p: List<List<Double>>) = p.map { Pt(it[0], it[1]) }
    fun toLists(p: List<Pt>) = p.map { listOf(it.x, it.y) }
}
