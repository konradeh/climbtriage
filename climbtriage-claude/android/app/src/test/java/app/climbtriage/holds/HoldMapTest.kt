package app.climbtriage.holds

import app.climbtriage.contracts.CoordinateSystem
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.HoldColor
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.Provenance
import app.climbtriage.geometry.Polygons
import app.climbtriage.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldMapTest {
    private val model = Provenance("model", "facebook/sam3.1", "gateway", "run_1")

    private fun hold(cx: Double, cy: Double, lab: List<Double>? = null, kind: String = "hold") = Hold(
        id = Ids.new("h"), kind = kind, coords = CoordinateSystem.WALL_NORM,
        polygon = Polygons.toLists(Polygons.box(cx, cy, 0.02, 0.02)), provenance = model,
        color = lab?.let { HoldColor(it, "", "median_lab.v1") },
    )

    @Test fun rawIsNeverMutatedAndFoldIsDeterministic() {
        val raw = listOf(hold(0.5, 0.9), hold(0.5, 0.5))
        val log = listOf(HoldMap.removeHold(1, raw[0].id), HoldMap.addMember(2, raw[1].id))
        val a = HoldMap.fold(raw, log)
        val b = HoldMap.fold(raw, log.reversed())       // order comes from seq, not list order
        assertEquals(a, b)
        assertFalse(raw[0].retired)
        assertTrue(a.hold(raw[0].id)!!.retired)
        assertEquals(setOf(raw[1].id), a.route.members)
    }

    @Test fun editKeepsIdSplitAndMergeMintNewIdsWithLineage() {
        val raw = listOf(hold(0.3, 0.3), hold(0.36, 0.3))
        val edited = HoldMap.editHold(1, raw[0].id, Polygons.box(0.3, 0.3, 0.03, 0.03))
        var s = HoldMap.fold(raw, listOf(edited))
        assertEquals(raw[0].id, s.live[0].id)

        val merge = HoldMap.mergeHolds(2, s.hold(raw[0].id)!!, s.hold(raw[1].id)!!)
        s = HoldMap.fold(raw, listOf(edited, merge))
        val merged = s.live.single()
        assertEquals(listOf(raw[0].id, raw[1].id), merged.parents)
        assertTrue(merged.id !in raw.map { it.id })

        val split = HoldMap.splitHold(3, merged, Pt(0.33, 0.0), Pt(0.33, 1.0))!!
        s = HoldMap.fold(raw, listOf(edited, merge, split))
        assertEquals(2, s.live.size)
        assertTrue(s.live.all { it.parents == listOf(merged.id) })
        assertEquals(5, s.holds.size)       // nothing is ever deleted from the history
    }

    @Test fun routeReferencesFollowSplitAndDropOnRemove() {
        val raw = listOf(hold(0.5, 0.2), hold(0.5, 0.8))
        val log = mutableListOf(HoldMap.setFinish(1, listOf(raw[0].id)), HoldMap.setStart(2, listOf(raw[1].id)))
        var s = HoldMap.fold(raw, log)
        assertEquals(setOf(raw[0].id, raw[1].id), s.route.members)
        log += HoldMap.splitHold(3, s.hold(raw[0].id)!!, Pt(0.5, 0.0), Pt(0.5, 1.0))!!
        s = HoldMap.fold(raw, log)
        assertEquals(2, s.route.finish.size)
        assertTrue(s.route.finish.none { it == raw[0].id })
        log += HoldMap.removeHold(4, raw[1].id)
        s = HoldMap.fold(raw, log)
        assertTrue(s.route.start.isEmpty())
        assertFalse(raw[1].id in s.route.members)
    }

    @Test fun finishIsExplicitNeverTheHighestHold() {
        val raw = listOf(hold(0.5, 0.1), hold(0.5, 0.5), hold(0.5, 0.9))
        val s = HoldMap.fold(raw, listOf(HoldMap.addMember(1, raw[0].id), HoldMap.addMember(2, raw[1].id)))
        assertTrue(s.route.finish.isEmpty())
    }

    @Test fun displayNumbersAreBottomToTopAndSeparateFromIds() {
        val raw = listOf(hold(0.5, 0.2), hold(0.2, 0.8), hold(0.6, 0.8))
        val n = DisplayNumbering.number(raw, raw.map { it.id }.toSet())
        assertEquals(1, n[raw[1].id]); assertEquals(2, n[raw[2].id]); assertEquals(3, n[raw[0].id])
    }

    @Test fun routeSuggestionByColourExcludesOtherColoursAndVolumes() {
        val green = listOf(55.0, -40.0, 30.0)
        val raw = listOf(hold(0.1, 0.1, green), hold(0.2, 0.2, listOf(56.0, -38.0, 33.0)),
            hold(0.3, 0.3, listOf(50.0, 60.0, 40.0)), hold(0.4, 0.4, green, kind = "volume"))
        val s = RouteSuggest.suggest(raw, raw[0].id)
        assertEquals(setOf(raw[0].id, raw[1].id), s.toSet())
    }
}
