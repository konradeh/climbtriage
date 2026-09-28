package app.climbtriage.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WallImageTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun medianRemovesAMovingObject() {
        val w = 10; val h = 10
        val wall = rgb(100, 100, 100)
        val frames = (0 until 5).map { k ->
            IntArray(w * h) { i -> if (i % w == k * 2) rgb(255, 0, 0) else wall }   // a "climber" column that moves
        }
        val out = WallImage.median(frames)
        assertTrue(out.all { it == wall })
    }

    /** A textured scene; frame k is the scene shifted by (dx, dy) — a camera pan. */
    private fun scene(w: Int, h: Int, dx: Int, dy: Int, seed: Int = 7): FloatArray {
        val rnd = Random(seed)
        val base = FloatArray((w + 40) * (h + 40)) { rnd.nextFloat() * 255f }
        return FloatArray(w * h) { i -> base[(i / w + 20 + dy) * (w + 40) + (i % w + 20 + dx)] }
    }

    @Test fun stillCameraIsStill() {
        val w = 64; val h = 48
        val check = StillnessCheck()
        val ref = check.gradient(scene(w, h, 0, 0), w, h)
        val frames = (0 until 6).map { check.gradient(scene(w, h, 0, 0), w, h) }
        val v = check.check(ref, frames, w, h, List(6) { null })
        assertFalse(v.anyMoved)
        assertEquals(0.0, v.maxShiftNorm, 1e-12)
    }

    @Test fun panIsDetected() {
        val w = 64; val h = 48
        val check = StillnessCheck()
        val ref = check.gradient(scene(w, h, 0, 0), w, h)
        val frames = listOf(0, 0, 0, 4, 0).map { dx -> check.gradient(scene(w, h, dx, 0), w, h) }
        val v = check.check(ref, frames, w, h, List(5) { null })
        assertTrue(v.frames[3].moved)
        assertEquals(4, kotlin.math.abs(v.frames[3].shiftX))
        assertFalse(v.frames[0].moved)
    }
}
