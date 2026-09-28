package app.climbtriage.holds

import app.climbtriage.contracts.CoordinateSystem
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.HoldColor
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.Provenance
import app.climbtriage.geometry.ColorLab
import app.climbtriage.geometry.Polygons
import app.climbtriage.geometry.Pt

/**
 * Offline **heuristic** hold proposer: "find blobs the colour of the hold I tapped".
 *
 * This is deliberately not called a detector. It has not been evaluated against the targets in
 * docs/07, it misses chalked or multi-colour holds, and its outlines are convex hulls. Output is
 * stamped `provenance.kind = "heuristic"` so the UI and exports can say so.
 */
class ColorAssist(private val config: Config = Config()) {
    data class Config(
        val maxLabDistance: Double = 20.0,
        val minChroma: Double = 12.0,          // greys/whites are wall, not a hold colour
        val minAreaFrac: Double = 0.00015,
        val maxAreaFrac: Double = 0.04,
        val version: String = "colourassist.v1",
    )

    val provenanceName = "heuristic.colourassist"

    fun seedColor(argb: IntArray, w: Int, h: Int, at: Pt, radius: Int = 3): DoubleArray {
        val cx = (at.x * w).toInt().coerceIn(0, w - 1)
        val cy = (at.y * h).toInt().coerceIn(0, h - 1)
        val labs = ArrayList<DoubleArray>()
        for (y in (cy - radius)..(cy + radius)) for (x in (cx - radius)..(cx + radius)) {
            if (x in 0 until w && y in 0 until h) labs += ColorLab.fromArgb(argb[y * w + x])
        }
        return DoubleArray(3) { c -> labs.map { it[c] }.sorted()[labs.size / 2] }
    }

    /** Candidate holds (in the image's normalised coordinates) whose colour matches [seed]. */
    fun propose(argb: IntArray, w: Int, h: Int, seed: DoubleArray, runId: String): List<Hold> {
        val seedList = seed.toList()
        val match = BooleanArray(w * h)
        val labCache = arrayOfNulls<DoubleArray>(w * h)
        for (i in 0 until w * h) {
            val lab = ColorLab.fromArgb(argb[i])
            labCache[i] = lab
            match[i] = ColorLab.chroma(lab) >= config.minChroma &&
                ColorLab.holdDistance(lab.toList(), seedList) <= config.maxLabDistance
        }
        val provenance = Provenance(kind = "heuristic", name = provenanceName, version = config.version, runId = runId)
        val seen = BooleanArray(w * h)
        val out = ArrayList<Hold>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!match[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            val boundary = ArrayList<Pt>()
            var area = 0
            val labs = ArrayList<DoubleArray>()
            while (top > 0) {
                val i = stack[--top]
                area++
                if (labs.size < 400) labs += labCache[i]!!
                val x = i % w
                val y = i / w
                var edge = false
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) { edge = true; continue }
                    val j = ny * w + nx
                    if (!match[j]) { edge = true; continue }
                    if (!seen[j]) { seen[j] = true; stack[top++] = j }
                }
                if (edge) boundary += Pt((x + 0.5) / w, (y + 0.5) / h)
            }
            val frac = area.toDouble() / (w * h)
            if (frac < config.minAreaFrac || frac > config.maxAreaFrac) continue
            val hull = Polygons.convexHull(boundary)
            if (hull.size < 3) continue
            val median = DoubleArray(3) { c -> labs.map { it[c] }.sorted()[labs.size / 2] }
            out += Hold(
                id = Ids.new("h"), kind = "hold", coords = CoordinateSystem.WALL_NORM,
                polygon = Polygons.toLists(hull), bbox = Polygons.bbox(hull),
                color = HoldColor(median.toList(), "", "median_lab.v1"),
                confidence = null, provenance = provenance,
            )
        }
        return out
    }

    private companion object {
        val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
