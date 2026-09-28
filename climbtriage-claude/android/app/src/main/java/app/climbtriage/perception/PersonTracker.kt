package app.climbtriage.perception

import app.climbtriage.contracts.Landmark
import app.climbtriage.contracts.LostInterval
import app.climbtriage.contracts.PoseSample
import app.climbtriage.contracts.Validity
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Follows **one user-selected person** through stored per-frame detections.
 *
 * MediaPipe returns several people per image with no identity, so association is ours. The rules
 * are chosen to fail visibly rather than silently:
 *  - a candidate is accepted only inside a motion gate that grows with elapsed *time* (µs from real
 *    timestamps, never frame counts), and only if it beats the runner-up by [Config.ambiguityMargin];
 *  - two plausible candidates → `AMBIGUOUS`, nothing in the gate → `LOST`; neither carries landmarks;
 *  - after a gap longer than [Config.maxGapUs], re-acquisition requires the frame to contain exactly
 *    one person of plausible size; otherwise the track stays lost until the user reselects.
 *
 * Pure and deterministic: re-running after a user correction needs no inference.
 */
class PersonTracker(private val config: Config = Config()) {

    data class Config(
        /** Allowed centre travel, in body heights per second, plus a fixed allowance. */
        val maxSpeedBodyHeightsPerS: Double = 2.5,
        val baseGateBodyHeights: Double = 0.35,
        /**
         * The gate stops growing here. Without a cap, a long occlusion makes anyone in the frame
         * "plausible" and the track silently jumps to a bystander — the failure this class exists
         * to prevent. Beyond the cap the user reselects.
         */
        val maxGateBodyHeights: Double = 1.0,
        val minSizeRatio: Double = 0.6,
        val maxSizeRatio: Double = 1.7,
        val ambiguityMargin: Double = 0.35,
        val maxGapUs: Long = 1_500_000,
        val lowConfidenceVisibility: Double = 0.5,
        val version: String = "person-tracker.v1",
    )

    private data class State(val bbox: List<Double>, val landmarks: List<Landmark>, val tUs: Long, val velX: Double, val velY: Double)

    data class Result(val samples: List<PoseSample>, val lostIntervals: List<LostInterval>)

    fun track(frames: List<FrameDetections>, seedFrame: Int, seedDetection: Int): Result {
        require(seedFrame in frames.indices) { "seed frame out of range" }
        val seed = frames[seedFrame]
        require(seed.analyzed && seedDetection in seed.people.indices) { "seed must be a detected person" }
        val forward = run(frames.subList(seedFrame, frames.size), seedDetection)
        val backward = run(frames.subList(0, seedFrame + 1).asReversed(), seedDetection).asReversed()
        val samples = backward.dropLast(1) + forward
        return Result(samples, lostIntervals(samples))
    }

    private fun run(frames: List<FrameDetections>, seedDetection: Int): List<PoseSample> {
        val out = ArrayList<PoseSample>(frames.size)
        val first = frames[0]
        val seedPerson = first.people[seedDetection]
        var state = State(seedPerson.bbox, seedPerson.landmarks, first.tUs, 0.0, 0.0)
        out += sample(first, seedPerson, Validity.VALID, 0.0)
        // People seen alongside the climber in the last accepted frame. While the climber is not
        // visible, a candidate nearer one of them than the climber's last position is them, not us.
        var others: List<Pair<Double, Double>> = first.people.filterIndexed { i, _ -> i != seedDetection }.map { center(it.bbox) }
        var lastWasAccepted = true

        for (frame in frames.drop(1)) {
            if (!frame.analyzed) {
                out += empty(frame, Validity.NOT_ANALYZED)
                lastWasAccepted = false
                continue
            }
            val dtUs = abs(frame.tUs - state.tUs)
            val dtS = dtUs / 1e6
            val bodyH = max(state.bbox[3], 0.05)
            val (lx, ly) = center(state.bbox)
            val (px, py) = (lx + state.velX * dtS) to (ly + state.velY * dtS)
            val gate = minOf(config.baseGateBodyHeights + config.maxSpeedBodyHeightsPerS * dtS, config.maxGateBodyHeights)

            val scored = frame.people.mapNotNull { person ->
                val ratio = person.bbox[3] / bodyH
                if (ratio < config.minSizeRatio || ratio > config.maxSizeRatio) return@mapNotNull null
                val (cx, cy) = center(person.bbox)
                val d = hypot(cx - px, cy - py) / bodyH
                if (d > gate) return@mapNotNull null
                if (!lastWasAccepted && others.any { (ox, oy) -> hypot(cx - ox, cy - oy) < hypot(cx - lx, cy - ly) }) {
                    return@mapNotNull null
                }
                val cost = d + keypointDistance(state.landmarks, person.landmarks, bodyH) + (1.0 - iou(state.bbox, person.bbox))
                person to cost
            }.sortedBy { it.second }

            val longGap = dtUs > config.maxGapUs
            val best = scored.firstOrNull()
            val accept = when {
                best == null -> false
                longGap -> frame.people.size == 1 && scored.size == 1        // strict re-acquisition
                scored.size == 1 -> true
                else -> scored[1].second - best.second >= config.ambiguityMargin
            }
            if (accept) {
                val person = best!!.first
                val (cx, cy) = center(person.bbox)
                val (ox, oy) = center(state.bbox)
                val (vx, vy) = if (dtS > 0 && !longGap) ((cx - ox) / dtS) to ((cy - oy) / dtS) else 0.0 to 0.0
                // Light damping keeps one noisy detection from throwing the prediction.
                state = State(person.bbox, person.landmarks, frame.tUs, 0.5 * state.velX + 0.5 * vx, 0.5 * state.velY + 0.5 * vy)
                val meanVis = keyLandmarks(person.landmarks).map { it.visibility ?: 1.0 }.average().takeIf { !it.isNaN() } ?: 0.0
                val validity = if (meanVis >= config.lowConfidenceVisibility) Validity.VALID else Validity.LOW_CONFIDENCE
                out += sample(frame, person, validity, best.second)
                others = frame.people.filter { it !== person }.map { center(it.bbox) }
                lastWasAccepted = true
            } else {
                out += empty(frame, if (scored.size >= 2) Validity.AMBIGUOUS else Validity.LOST)
                lastWasAccepted = false
            }
        }
        return out
    }

    private fun sample(frame: FrameDetections, p: PersonDetection, v: Validity, cost: Double) =
        PoseSample(tUs = frame.tUs, frameIndex = frame.frameIndex, validity = v, landmarks = p.landmarks,
            bbox = p.bbox, associationCost = cost)

    private fun empty(frame: FrameDetections, v: Validity) =
        PoseSample(tUs = frame.tUs, frameIndex = frame.frameIndex, validity = v, landmarks = emptyList(), bbox = null)

    private fun center(b: List<Double>) = (b[0] + b[2] / 2) to (b[1] + b[3] / 2)

    private fun keyLandmarks(l: List<Landmark>) = l.filter { it.name in TORSO }

    private fun keypointDistance(a: List<Landmark>, b: List<Landmark>, bodyH: Double): Double {
        val bm = b.associateBy { it.name }
        val d = a.filter { it.name in TORSO && it.visibleEnough() }.mapNotNull { la ->
            bm[la.name]?.takeIf { it.visibleEnough() }?.let { lb -> hypot(la.x - lb.x, la.y - lb.y) / bodyH }
        }
        return if (d.isEmpty()) 0.5 else d.average()
    }

    private fun iou(a: List<Double>, b: List<Double>): Double {
        val x0 = max(a[0], b[0]); val y0 = max(a[1], b[1])
        val x1 = min(a[0] + a[2], b[0] + b[2]); val y1 = min(a[1] + a[3], b[1] + b[3])
        val inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
        val union = a[2] * a[3] + b[2] * b[3] - inter
        return if (union > 0) inter / union else 0.0
    }

    companion object {
        private val TORSO = setOf("left_shoulder", "right_shoulder", "left_hip", "right_hip")

        /** Consecutive non-valid samples, as intervals on real timestamps. */
        fun lostIntervals(samples: List<PoseSample>): List<LostInterval> {
            val out = ArrayList<LostInterval>()
            var start: PoseSample? = null
            var last: PoseSample? = null
            var reason = ""
            fun reasonOf(v: Validity) = when (v) {
                Validity.AMBIGUOUS -> "ambiguous"
                Validity.NOT_ANALYZED -> "not_analyzed"
                else -> "no_candidate"
            }
            for (s in samples) {
                val bad = s.validity == Validity.LOST || s.validity == Validity.AMBIGUOUS || s.validity == Validity.NOT_ANALYZED
                if (bad && start != null && reasonOf(s.validity) == reason) {
                    last = s
                } else {
                    if (start != null) out += LostInterval(start.tUs, last!!.tUs, reason)
                    start = if (bad) s else null
                    last = if (bad) s else null
                    reason = if (bad) reasonOf(s.validity) else ""
                }
            }
            if (start != null) out += LostInterval(start.tUs, last!!.tUs, reason)
            return out
        }
    }
}
