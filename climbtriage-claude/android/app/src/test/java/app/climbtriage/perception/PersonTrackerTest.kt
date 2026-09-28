package app.climbtriage.perception

import app.climbtriage.contracts.Landmark
import app.climbtriage.contracts.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic detections: these test the association rules, not pose-model quality. */
class PersonTrackerTest {
    private fun person(cx: Double, cy: Double, h: Double = 0.4, vis: Double = 0.9): PersonDetection {
        val w = h * 0.4
        val lms = listOf(
            Landmark("left_shoulder", cx - w / 3, cy - h / 3, visibility = vis),
            Landmark("right_shoulder", cx + w / 3, cy - h / 3, visibility = vis),
            Landmark("left_hip", cx - w / 4, cy + h / 10, visibility = vis),
            Landmark("right_hip", cx + w / 4, cy + h / 10, visibility = vis),
        )
        return PersonDetection(listOf(cx - w / 2, cy - h / 2, w, h), lms)
    }

    private fun frame(i: Int, tUs: Long, vararg people: PersonDetection, analyzed: Boolean = true) =
        FrameDetections(i, tUs, analyzed, people.toList())

    private val dt = 100_000L   // 10 Hz analysis cadence

    @Test fun followsSelectedPersonNotTheOtherOne() {
        val frames = (0 until 20).map { i ->
            frame(i, i * dt, person(0.3, 0.5 - i * 0.005), person(0.8, 0.6))
        }
        val r = PersonTracker().track(frames, seedFrame = 0, seedDetection = 0)
        assertTrue(r.samples.all { it.validity == Validity.VALID })
        assertTrue(r.samples.all { it.bbox!![0] < 0.5 })
        assertTrue(r.lostIntervals.isEmpty())
    }

    @Test fun crossingIsMarkedAmbiguousInsteadOfSwitching() {
        // Climber at x=0.4 stays; a second person walks from 0.9 through 0.4 and on to 0.0.
        val frames = (0 until 30).map { i ->
            val walker = 0.9 - i * 0.03
            frame(i, i * dt, person(0.4, 0.5), person(walker, 0.5))
        }
        val r = PersonTracker().track(frames, seedFrame = 0, seedDetection = 0)
        val valid = r.samples.filter { it.validity == Validity.VALID }
        // Whenever a sample is accepted it must be the climber (x≈0.4), never the walker.
        assertTrue(valid.all { kotlin.math.abs(it.bbox!![0] + it.bbox[2] / 2 - 0.4) < 0.02 })
        assertTrue(r.samples.any { it.validity == Validity.AMBIGUOUS })
        assertTrue(r.samples.filter { it.validity != Validity.VALID }.all { it.landmarks.isEmpty() })
        assertTrue(r.lostIntervals.any { it.reason == "ambiguous" })
    }

    @Test fun longGapWithOthersPresentStaysLost() {
        val frames = mutableListOf(frame(0, 0, person(0.3, 0.5)), frame(1, dt, person(0.3, 0.5)))
        // Climber occluded for 3 s while someone else stands elsewhere, then two candidates appear.
        for (i in 2 until 32) frames += frame(i, i * dt, person(0.8, 0.5))
        frames += frame(32, 32 * dt, person(0.32, 0.5), person(0.8, 0.5))
        val r = PersonTracker().track(frames, 0, 0)
        assertEquals(Validity.LOST, r.samples.last().validity)
        assertTrue(r.samples.subList(2, 32).none { it.validity == Validity.VALID })
    }

    @Test fun longGapAloneReacquires() {
        val frames = mutableListOf(frame(0, 0, person(0.3, 0.5)))
        for (i in 1 until 25) frames += frame(i, i * dt)              // nobody detected for 2.4 s
        frames += frame(25, 25 * dt, person(0.33, 0.45))
        val r = PersonTracker().track(frames, 0, 0)
        assertEquals(Validity.VALID, r.samples.last().validity)
        assertEquals(1, r.lostIntervals.size)
        assertEquals(dt, r.lostIntervals[0].startUs)
        assertEquals(24 * dt, r.lostIntervals[0].endUs)
    }

    @Test fun gateUsesRealTimestampsNotFrameCount() {
        // Same displacement (0.875 body heights); at 10 Hz it is a teleport, after a 1 s VFR gap it
        // is plausible motion.
        val fast = listOf(frame(0, 0, person(0.2, 0.5)), frame(1, dt, person(0.55, 0.5)))
        assertEquals(Validity.LOST, PersonTracker().track(fast, 0, 0).samples[1].validity)
        val slow = listOf(frame(0, 0, person(0.2, 0.5)), frame(1, 1_000_000, person(0.55, 0.5)))
        assertEquals(Validity.VALID, PersonTracker().track(slow, 0, 0).samples[1].validity)
    }

    @Test fun occlusionNeverAdoptsABystanderSeenAlongside() {
        // Climber at 0.3 and a spotter at 0.55 (0.6 body heights away, inside the capped gate).
        val frames = mutableListOf(frame(0, 0, person(0.3, 0.5), person(0.55, 0.5)))
        for (i in 1 until 12) frames += frame(i, i * dt, person(0.55, 0.5))       // climber occluded
        val r = PersonTracker().track(frames, 0, 0)
        assertTrue(r.samples.drop(1).none { it.validity == Validity.VALID })
    }

    @Test fun gateGrowthIsCapped() {
        // After 3 s anybody could be "plausible" without a cap; 1.25 body heights away must stay lost.
        val frames = mutableListOf(frame(0, 0, person(0.3, 0.5)))
        for (i in 1 until 30) frames += frame(i, i * dt)
        frames += frame(30, 30 * dt, person(0.8, 0.5))
        assertEquals(Validity.LOST, PersonTracker().track(frames, 0, 0).samples.last().validity)
    }

    @Test fun seedMidClipTracksBothDirections() {
        val frames = (0 until 10).map { i -> frame(i, i * dt, person(0.2 + i * 0.01, 0.5), person(0.8, 0.5)) }
        val r = PersonTracker().track(frames, seedFrame = 5, seedDetection = 0)
        assertEquals(10, r.samples.size)
        assertEquals((0 until 10).map { it * dt }, r.samples.map { it.tUs })
        assertTrue(r.samples.all { it.validity == Validity.VALID && it.bbox!![0] < 0.5 })
    }

    @Test fun unanalysedFramesAreNotAnalyzedNotLost() {
        val frames = listOf(frame(0, 0, person(0.3, 0.5)), frame(1, dt, analyzed = false), frame(2, 2 * dt, person(0.3, 0.5)))
        val r = PersonTracker().track(frames, 0, 0)
        assertEquals(Validity.NOT_ANALYZED, r.samples[1].validity)
        assertEquals("not_analyzed", r.lostIntervals.single().reason)
        assertEquals(Validity.VALID, r.samples[2].validity)
    }

    @Test fun lowVisibilityIsLowConfidence() {
        val frames = listOf(frame(0, 0, person(0.3, 0.5)), frame(1, dt, person(0.3, 0.5, vis = 0.2)))
        assertEquals(Validity.LOW_CONFIDENCE, PersonTracker().track(frames, 0, 0).samples[1].validity)
    }
}
