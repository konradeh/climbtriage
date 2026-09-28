package app.climbtriage.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonsTest {
    private val square = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))

    @Test fun areaCentroidContains() {
        assertEquals(1.0, Polygons.area(square), 1e-12)
        assertEquals(0.5, Polygons.centroid(square).x, 1e-12)
        assertTrue(Polygons.contains(square, Pt(0.5, 0.5)))
        assertFalse(Polygons.contains(square, Pt(1.5, 0.5)))
        assertEquals(0.5, Polygons.distance(square, Pt(1.5, 0.5)), 1e-12)
    }

    @Test fun splitThroughMiddleConservesArea() {
        val (a, b) = Polygons.split(square, Pt(0.5, -1.0), Pt(0.5, 2.0))!!
        assertEquals(0.5, Polygons.area(a), 1e-12)
        assertEquals(0.5, Polygons.area(b), 1e-12)
    }

    @Test fun splitMissingThePolygonIsRejected() {
        assertNull(Polygons.split(square, Pt(2.0, 0.0), Pt(2.0, 1.0)))
    }

    @Test fun splitNonConvex() {
        // An L shape cut vertically.
        val l = listOf(Pt(0.0, 0.0), Pt(2.0, 0.0), Pt(2.0, 1.0), Pt(1.0, 1.0), Pt(1.0, 2.0), Pt(0.0, 2.0))
        val parts = Polygons.split(l, Pt(1.5, -1.0), Pt(1.5, 3.0))
        assertNotNull(parts)
        assertEquals(Polygons.area(l), Polygons.area(parts!!.first) + Polygons.area(parts.second), 1e-9)
    }

    @Test fun mergeHullCoversBoth() {
        val a = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0))
        val b = listOf(Pt(2.0, 0.0), Pt(3.0, 0.0), Pt(3.0, 1.0))
        val hull = Polygons.mergeHull(a, b)
        (a + b).forEach { assertTrue(Polygons.distance(hull, it) < 1e-9) }
    }
}
