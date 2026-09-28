package app.climbtriage.ui

import app.climbtriage.contracts.PoseSample
import app.climbtriage.contracts.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearestSampleTest {
    private val samples = listOf(0L, 100_000L, 200_000L, 900_000L).mapIndexed { i, t ->
        PoseSample(t, i, Validity.VALID, landmarks = emptyList())
    }

    @Test fun picksNearestWithinTolerance() {
        assertEquals(100_000L, nearestSample(samples, 130_000L, 75_000L)?.tUs)
        assertEquals(200_000L, nearestSample(samples, 170_000L, 75_000L)?.tUs)
    }

    @Test fun gapsDrawNothingInsteadOfStaleSkeletons() {
        assertNull(nearestSample(samples, 550_000L, 75_000L))
    }
}
