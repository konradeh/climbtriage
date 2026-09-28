package app.climbtriage.holds

import app.climbtriage.contracts.Correction
import app.climbtriage.contracts.CoordinateSystem
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.Provenance
import app.climbtriage.geometry.Polygons
import app.climbtriage.geometry.Pt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Route membership and rules — separate from the wall's holds by design. */
data class RouteState(
    val members: Set<String> = emptySet(),
    val start: List<String> = emptyList(),
    val finish: List<String> = emptyList(),
    val routeType: String = "boulder",
)

data class HoldMapState(val holds: List<Hold>, val route: RouteState) {
    val live: List<Hold> get() = holds.filter { !it.retired }
    fun hold(id: String) = holds.firstOrNull { it.id == id }
}

/**
 * The effective hold map is `fold(raw detections, corrections)`. Raw runs are never mutated;
 * every edit is an append-only [Correction] whose payload carries any new IDs, so replaying the log
 * is deterministic. Hold IDs are immutable: an outline edit keeps the ID (same physical hold);
 * split/merge retire the originals and mint new IDs with `parents` lineage.
 */
object HoldMap {
    const val MAX_START = 4
    const val MAX_FINISH = 2

    fun fold(raw: List<Hold>, corrections: List<Correction>): HoldMapState {
        var state = HoldMapState(raw, RouteState())
        for (c in corrections.sortedBy { it.seq }) state = apply(state, c)
        return state
    }

    fun apply(s: HoldMapState, c: Correction): HoldMapState {
        val p = c.payload
        return when (c.op) {
            "ADD_HOLD" -> s.copy(holds = s.holds + Hold(
                id = p.str("holdId"), kind = p.strOr("kind", "hold"), coords = CoordinateSystem.WALL_NORM,
                polygon = p.polygon("polygon"), bbox = Polygons.bbox(Polygons.fromLists(p.polygon("polygon"))),
                provenance = userProvenance(c)))
            "REMOVE_HOLD" -> retire(s, setOf(p.str("holdId")), replacement = emptyList())
            "EDIT_HOLD" -> s.copy(holds = s.holds.map {
                if (it.id == p.str("holdId") && !it.retired) {
                    it.copy(polygon = p.polygon("polygon"), bbox = Polygons.bbox(Polygons.fromLists(p.polygon("polygon"))))
                } else it
            })
            "SET_HOLD_KIND" -> s.copy(holds = s.holds.map { if (it.id == p.str("holdId")) it.copy(kind = p.str("kind")) else it })
            "SPLIT_HOLD" -> {
                val original = s.hold(p.str("holdId"))?.takeIf { !it.retired } ?: return s
                val ids = p.strings("newIds")
                val polys = p["polygons"]!!.jsonArray.map { poly -> poly.jsonArray.map { pt -> pt.jsonArray.map { it.jsonPrimitive.double } } }
                val parts = ids.zip(polys).map { (id, poly) ->
                    original.copy(id = id, polygon = poly, bbox = Polygons.bbox(Polygons.fromLists(poly)),
                        parents = listOf(original.id), provenance = userProvenance(c), confidence = null)
                }
                retire(s, setOf(original.id), replacement = ids).let { it.copy(holds = it.holds + parts) }
            }
            "MERGE_HOLDS" -> {
                val ids = p.strings("holdIds").filter { id -> s.hold(id)?.retired == false }
                if (ids.size < 2) return s
                val first = s.hold(ids[0])!!
                val merged = first.copy(id = p.str("newId"), polygon = p.polygon("polygon"),
                    bbox = Polygons.bbox(Polygons.fromLists(p.polygon("polygon"))), parents = ids,
                    provenance = userProvenance(c), confidence = null)
                retire(s, ids.toSet(), replacement = listOf(merged.id)).let { it.copy(holds = it.holds + merged) }
            }
            "ADD_ROUTE_MEMBER" -> if (s.hold(p.str("holdId"))?.retired == false)
                s.copy(route = s.route.copy(members = s.route.members + p.str("holdId"))) else s
            "REMOVE_ROUTE_MEMBER" -> {
                val id = p.str("holdId")
                s.copy(route = s.route.copy(members = s.route.members - id, start = s.route.start - id, finish = s.route.finish - id))
            }
            "SET_START" -> {
                val ids = p.strings("holdIds").filter { s.hold(it)?.retired == false }.take(MAX_START)
                s.copy(route = s.route.copy(start = ids, members = s.route.members + ids))
            }
            "SET_FINISH" -> {
                val ids = p.strings("holdIds").filter { s.hold(it)?.retired == false }.take(MAX_FINISH)
                s.copy(route = s.route.copy(finish = ids, members = s.route.members + ids))
            }
            "SET_ROUTE_TYPE" -> s.copy(route = s.route.copy(routeType = p.str("routeType")))
            else -> s     // RESELECT_PERSON etc. act on other state
        }
    }

    /** Retire holds; route references move to the replacement IDs (split/merge) or are dropped. */
    private fun retire(s: HoldMapState, ids: Set<String>, replacement: List<String>): HoldMapState {
        fun remap(list: Collection<String>): List<String> =
            list.flatMap { if (it in ids) replacement else listOf(it) }.distinct()
        val wasMember = s.route.members.any { it in ids }
        val members = s.route.members.filterNot { it in ids }.toSet() + (if (wasMember) replacement else emptyList())
        return HoldMapState(
            holds = s.holds.map { if (it.id in ids) it.copy(retired = true) else it },
            route = s.route.copy(members = members, start = remap(s.route.start).take(MAX_START),
                finish = remap(s.route.finish).take(MAX_FINISH)),
        )
    }

    private fun userProvenance(c: Correction) = Provenance(kind = "user", name = "hold-editor", version = "1", runId = c.id)

    // ── correction builders: the only way the UI changes the hold map ────────
    fun addHold(seq: Int, polygon: List<Pt>, kind: String = "hold") = correction(seq, "wall", "ADD_HOLD", buildJsonObject {
        put("holdId", Ids.new("h")); put("kind", kind); putPolygon("polygon", polygon)
    })

    fun removeHold(seq: Int, holdId: String) = correction(seq, "wall", "REMOVE_HOLD", buildJsonObject { put("holdId", holdId) })

    fun editHold(seq: Int, holdId: String, polygon: List<Pt>) = correction(seq, "wall", "EDIT_HOLD", buildJsonObject {
        put("holdId", holdId); putPolygon("polygon", polygon)
    })

    fun setKind(seq: Int, holdId: String, kind: String) = correction(seq, "wall", "SET_HOLD_KIND", buildJsonObject {
        put("holdId", holdId); put("kind", kind)
    })

    /** Null when the line does not cut the hold into two meaningful parts. */
    fun splitHold(seq: Int, hold: Hold, a: Pt, b: Pt): Correction? {
        val (left, right) = Polygons.split(Polygons.fromLists(hold.polygon), a, b) ?: return null
        return correction(seq, "wall", "SPLIT_HOLD", buildJsonObject {
            put("holdId", hold.id)
            putJsonArray("newIds") { add(JsonPrimitive(Ids.new("h"))); add(JsonPrimitive(Ids.new("h"))) }
            put("polygons", JsonArray(listOf(polyJson(left), polyJson(right))))
            put("line", JsonArray(listOf(polyJsonPt(a), polyJsonPt(b))))
        })
    }

    fun mergeHolds(seq: Int, a: Hold, b: Hold) = correction(seq, "wall", "MERGE_HOLDS", buildJsonObject {
        putJsonArray("holdIds") { add(JsonPrimitive(a.id)); add(JsonPrimitive(b.id)) }
        put("newId", Ids.new("h"))
        putPolygon("polygon", Polygons.mergeHull(Polygons.fromLists(a.polygon), Polygons.fromLists(b.polygon)))
        put("method", "convex_hull")
    })

    fun addMember(seq: Int, holdId: String) = correction(seq, "route", "ADD_ROUTE_MEMBER", buildJsonObject { put("holdId", holdId) })
    fun removeMember(seq: Int, holdId: String) = correction(seq, "route", "REMOVE_ROUTE_MEMBER", buildJsonObject { put("holdId", holdId) })
    fun setStart(seq: Int, ids: List<String>) = correction(seq, "route", "SET_START", idsPayload(ids))
    fun setFinish(seq: Int, ids: List<String>) = correction(seq, "route", "SET_FINISH", idsPayload(ids))
    fun setRouteType(seq: Int, type: String) = correction(seq, "route", "SET_ROUTE_TYPE", buildJsonObject { put("routeType", type) })

    private fun idsPayload(ids: List<String>) = buildJsonObject { putJsonArray("holdIds") { ids.forEach { add(JsonPrimitive(it)) } } }

    private fun correction(seq: Int, target: String, op: String, payload: JsonObject) =
        Correction(id = Ids.new("cor"), seq = seq, createdAt = Instant.now().toString(), target = target, op = op, payload = payload)

    private fun polyJsonPt(p: Pt) = JsonArray(listOf(JsonPrimitive(p.x), JsonPrimitive(p.y)))
    private fun polyJson(p: List<Pt>) = JsonArray(p.map(::polyJsonPt))
    private fun kotlinx.serialization.json.JsonObjectBuilder.putPolygon(key: String, p: List<Pt>) = put(key, polyJson(p))

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.strOr(k: String, d: String) = this[k]?.jsonPrimitive?.contentOrNull ?: d
    private fun JsonObject.strings(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.polygon(k: String) = this[k]!!.jsonArray.map { pt -> pt.jsonArray.map { it.jsonPrimitive.double } }
}

/** Display numbers: bottom → top, left → right within a height band. Never used as identity. */
object DisplayNumbering {
    fun number(holds: List<Hold>, members: Set<String>, band: Double = 0.025): Map<String, Int> {
        val centred = holds.filter { it.id in members && !it.retired }
            .map { it.id to Polygons.centroid(Polygons.fromLists(it.polygon)) }
        val ordered = centred.sortedWith(compareBy({ -Math.round(it.second.y / band) }, { it.second.x }))
        return ordered.mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
    }
}

/** Suggest route members by colour similarity to a hold the user picked. The user confirms. */
object RouteSuggest {
    fun suggest(holds: List<Hold>, seedId: String, maxDistance: Double = 18.0): List<String> {
        val seed = holds.firstOrNull { it.id == seedId }?.color?.lab ?: return listOf(seedId)
        return holds.filter { h ->
            !h.retired && h.kind == "hold" && h.color != null &&
                app.climbtriage.geometry.ColorLab.holdDistance(h.color.lab, seed) <= maxDistance
        }.map { it.id }.let { if (seedId in it) it else it + seedId }
    }
}
