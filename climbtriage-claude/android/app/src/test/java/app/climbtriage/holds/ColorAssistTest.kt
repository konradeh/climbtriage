package app.climbtriage.holds

import app.climbtriage.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorAssistTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun findsSameColourBlobsOnly() {
        val w = 100; val h = 100
        val img = IntArray(w * h) { rgb(200, 200, 195) }                  // off-white wall
        fun blob(cx: Int, cy: Int, r: Int, c: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) img[y * w + x] = c
        }
        blob(20, 80, 5, rgb(40, 160, 60)); blob(60, 40, 4, rgb(45, 170, 55)); blob(80, 80, 5, rgb(200, 40, 40))
        val assist = ColorAssist()
        val seed = assist.seedColor(img, w, h, Pt(0.2, 0.8))
        val holds = assist.propose(img, w, h, seed, "run_x")
        assertEquals(2, holds.size)
        assertTrue(holds.all { it.provenance.kind == "heuristic" })
        assertTrue(holds.none { h -> h.polygon.any { it[0] > 0.7 && it[1] > 0.7 } })
    }
}
