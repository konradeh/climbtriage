package app.climbtriage.domain

import kotlin.math.abs
import kotlin.math.min

data class Fit(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun screen(p: Point) = Point(left + p.x * width, top + p.y * height)
    fun normalized(p: Point): Point? {
        if (width <= 0 || height <= 0) return null
        val result = Point((p.x-left)/width, (p.y-top)/height)
        return result.takeIf { it.x in 0f..1f && it.y in 0f..1f }
    }
    companion object {
        fun within(viewW: Float, viewH: Float, videoW: Int, videoH: Int): Fit {
            val scale = min(viewW/videoW, viewH/videoH)
            val w = videoW*scale; val h = videoH*scale
            return Fit((viewW-w)/2, (viewH-h)/2, w, h)
        }
    }
}

object Geometry {
    fun contains(ring: List<Point>, p: Point): Boolean {
        var inside = false
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[(i+1)%ring.size]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside = !inside
        }
        return inside
    }
    fun valid(ring: List<Point>): Boolean {
        if (ring.size < 3 || ring.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        var area = 0f
        for (i in ring.indices) { val a=ring[i]; val b=ring[(i+1)%ring.size]; area += a.x*b.y-b.x*a.y }
        if (abs(area) < .00002f) return false
        // Reject self-intersections instead of guessing the intended polygon.
        for (i in ring.indices) for (j in i+1 until ring.size) {
            if (j == i+1 || i == 0 && j == ring.lastIndex) continue
            val a=ring[i]; val b=ring[(i+1)%ring.size]; val c=ring[j]; val d=ring[(j+1)%ring.size]
            if (cross(a,b,c)*cross(a,b,d) <= 0 && cross(c,d,a)*cross(c,d,b) <= 0 &&
                maxOf(minOf(a.x,b.x),minOf(c.x,d.x)) <= minOf(maxOf(a.x,b.x),maxOf(c.x,d.x)) &&
                maxOf(minOf(a.y,b.y),minOf(c.y,d.y)) <= minOf(maxOf(a.y,b.y),maxOf(c.y,d.y))) return false
        }
        return true
    }
    private fun cross(a:Point,b:Point,c:Point) = (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
}

/** Split uses explicit replacement outlines so concave masks aren't silently
 * split with a convex-only approximation. The UI collects both child polygons.
 */
object Editor {
    fun change(session: Session, holds: List<Hold>, operation: String, affected: List<String>, timeUs: Long): Session {
        val wall = session.wall.copy(version_id=newId(), parent_version_id=session.wall.version_id, holds=holds)
        val ids = holds.map { it.id }.toSet()
        val route = session.route.copy(version_id=newId(), parent_version_id=session.route.version_id,
            wall_version_id=wall.version_id, members=session.route.members.intersect(ids),
            starts=session.route.starts.intersect(ids), finishes=session.route.finishes.intersect(ids))
        return session.copy(wall=wall, route=route, corrections=session.corrections + Correction(
            timestamp_us=timeUs, operation=operation, parent_version_id=session.wall.version_id,
            version_id=wall.version_id, affected_ids=affected))
    }
    fun route(session: Session, id: String, mode: String, timeUs: Long): Session {
        require(session.wall.holds.any { it.id == id })
        fun toggle(set: Set<String>) = if (id in set) set-id else set+id
        val old=session.route
        val members=if(mode=="member") toggle(old.members) else old.members+id
        val next=old.copy(version_id=newId(), parent_version_id=old.version_id, members=members,
            starts=(if(mode=="start") toggle(old.starts) else old.starts).intersect(members),
            finishes=(if(mode=="finish") toggle(old.finishes) else old.finishes).intersect(members))
        return session.copy(route=next, corrections=session.corrections+Correction(timestamp_us=timeUs,
            operation="route_$mode",parent_version_id=old.version_id,version_id=next.version_id,affected_ids=listOf(id)))
    }
}
