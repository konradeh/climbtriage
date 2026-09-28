package app.climbtriage.perception

import app.climbtriage.contracts.Landmark
import kotlinx.serialization.Serializable

/** MediaPipe Pose Landmarker's 33 landmarks, in model output order. */
val MEDIAPIPE33 = listOf(
    "nose", "left_eye_inner", "left_eye", "left_eye_outer", "right_eye_inner", "right_eye", "right_eye_outer",
    "left_ear", "right_ear", "mouth_left", "mouth_right", "left_shoulder", "right_shoulder", "left_elbow",
    "right_elbow", "left_wrist", "right_wrist", "left_pinky", "right_pinky", "left_index", "right_index",
    "left_thumb", "right_thumb", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle",
    "left_heel", "right_heel", "left_foot_index", "right_foot_index",
)

/** Skeleton edges drawn in replay, by landmark name. */
val SKELETON_EDGES = listOf(
    "left_shoulder" to "right_shoulder", "left_shoulder" to "left_elbow", "left_elbow" to "left_wrist",
    "right_shoulder" to "right_elbow", "right_elbow" to "right_wrist", "left_shoulder" to "left_hip",
    "right_shoulder" to "right_hip", "left_hip" to "right_hip", "left_hip" to "left_knee",
    "left_knee" to "left_ankle", "right_hip" to "right_knee", "right_knee" to "right_ankle",
    "left_ankle" to "left_heel", "left_heel" to "left_foot_index", "left_ankle" to "left_foot_index",
    "right_ankle" to "right_heel", "right_heel" to "right_foot_index", "right_ankle" to "right_foot_index",
    "left_wrist" to "left_index", "right_wrist" to "right_index",
)

/** One person found by the pose model in one frame. No identity: that is PersonTracker's job. */
@Serializable
data class PersonDetection(
    val bbox: List<Double>,             // [x, y, w, h] frame_norm, from the landmarks
    val landmarks: List<Landmark>,      // frame_norm
)

/** Everything the pose model returned for one analysed frame (raw, immutable). */
@Serializable
data class FrameDetections(
    val frameIndex: Int,
    val tUs: Long,
    val analyzed: Boolean,              // false = decode/inference failed or dropped; never "no people"
    val people: List<PersonDetection>,
)

@Serializable
data class DetectionRun(
    val runId: String,
    val model: String,
    val modelVersion: String,
    val cadenceHz: Double,
    val maxPeople: Int,
    val frames: List<FrameDetections>,
)

fun Landmark.visibleEnough(min: Double = 0.5): Boolean = (visibility ?: 1.0) >= min && (presence ?: 1.0) >= min

fun bboxOf(landmarks: List<Landmark>, minVisibility: Double = 0.3): List<Double>? {
    val pts = landmarks.filter { it.visibleEnough(minVisibility) }
    if (pts.size < 3) return null
    val x0 = pts.minOf { it.x }.coerceIn(0.0, 1.0)
    val y0 = pts.minOf { it.y }.coerceIn(0.0, 1.0)
    val x1 = pts.maxOf { it.x }.coerceIn(0.0, 1.0)
    val y1 = pts.maxOf { it.y }.coerceIn(0.0, 1.0)
    return listOf(x0, y0, x1 - x0, y1 - y0)
}
