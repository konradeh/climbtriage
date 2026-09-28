package app.climbtriage.domain

import kotlin.math.hypot

/** Conservative causal association, deliberately abstains at crossings/gaps.
 * Not an appearance re-identification model. All selection/loss is retained.
 */
object Tracking {
    fun select(frames: List<PoseFrame>, seeds: List<Seed>, aspect: Float): List<TrackedFrame> {
        val byTime = seeds.associateBy { it.timestamp_us }
        var previous: Person? = null
        var lastUs = 0L
        var segment: String? = null
        var lost = false
        return frames.map { frame ->
            val seed = byTime[frame.timestamp_us]
            if (seed != null) {
                previous = frame.people.getOrNull(seed.candidate_index)?.takeIf { it.center() != null }
                segment = seed.segment_id
                lost = previous == null
                lastUs = frame.timestamp_us
            } else if (previous != null && !lost) {
                val elapsed = frame.timestamp_us - lastUs
                val candidates = frame.people.mapIndexedNotNull { index, person ->
                    distance(previous!!, person, aspect)?.let { Triple(index, person, it) }
                }.sortedBy { it.third }
                val best = candidates.firstOrNull()
                val threshold = (.025f + elapsed / 1_000_000f * .6f).coerceAtMost(.14f)
                val ambiguous = best != null && candidates.drop(1).any {
                    it.third - best.third < .07f || centerDistance(best.second, it.second, aspect) < .16f
                }
                if (elapsed !in 1..250_000 || best == null || best.third > threshold || ambiguous) {
                    lost = true
                    previous = null
                } else {
                    previous = best.second
                    lastUs = frame.timestamp_us
                }
            }
            TrackedFrame(frame.timestamp_us, previous.takeUnless { lost }, segment,
                if (lost) "lost — reselect person" else if (segment == null) "select person" else "associated (unvalidated)")
        }
    }

    private fun distance(a: Person, b: Person, aspect: Float): Float? {
        val pairs = listOf(11, 12, 23, 24).mapNotNull { i ->
            val x = a.landmarks.getOrNull(i); val y = b.landmarks.getOrNull(i)
            if (x?.valid == true && y?.valid == true) hypot((x.x-y.x)*aspect, x.y-y.y) else null
        }
        return pairs.takeIf { it.size >= 3 }?.average()?.toFloat()
    }

    private fun centerDistance(a: Person, b: Person, aspect: Float): Float {
        val x = a.center() ?: return Float.MAX_VALUE
        val y = b.center() ?: return Float.MAX_VALUE
        return hypot((x.x-y.x)*aspect, x.y-y.y)
    }

    fun at(frames: List<TrackedFrame>, timeUs: Long): TrackedFrame? {
        val index = frames.binarySearch { it.timestampUs.compareTo(timeUs) }
        val sample = frames.getOrNull(if (index >= 0) index else -index - 2)
        return sample?.takeIf { timeUs - it.timestampUs in 0..150_000 }
    }
}
