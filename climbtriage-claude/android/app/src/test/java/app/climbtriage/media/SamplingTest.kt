package app.climbtriage.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    @Test fun cadenceFollowsTimestampsWithoutDrift() {
        // Variable frame rate: 30 fps for 1 s, then 60 fps for 0.5 s.
        val pts = (0 until 30).map { it * 33_333L } + (0 until 30).map { 1_000_000L + it * 16_667L }
        val times = MediaProbe.sampleIndices(pts, 10.0).map { pts[it] }
        // One sample per 100 ms grid cell: 1.5 s of video → 15 samples, each within one frame of its target.
        assertEquals(15, times.size)
        times.forEachIndexed { k, t -> assertTrue("sample $k at $t", t - k * 100_000L in 0L..33_333L) }
    }

    @Test fun framesSparserThanCadenceAreAllUsedOnce() {
        val pts = listOf(0L, 250_000L, 500_000L)
        assertEquals(listOf(0, 1, 2), MediaProbe.sampleIndices(pts, 10.0))
    }

    @Test fun emptyIsEmpty() = assertEquals(emptyList<Int>(), MediaProbe.sampleIndices(emptyList(), 10.0))
}
