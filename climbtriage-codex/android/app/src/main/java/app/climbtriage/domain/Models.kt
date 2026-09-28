package app.climbtriage.domain

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
const val SPACE = "upright_video_normalized"

@Serializable data class Point(val x: Float, val y: Float)
@Serializable data class Provenance(
    val provider: String, val model: String, val model_revision: String,
    val algorithm: String, val config_hash: String
)
@Serializable data class Capture(
    val schema_version: Int = 1, val id: String, val content_sha256: String,
    val width: Int, val height: Int, val rotation_degrees: Int, val first_pts_us: Long,
    val duration_us: Long, val decoded_frames: Int, val analyzed_frames: Int,
    val timebase: String = "media_presentation_microseconds",
    val mirrored: Boolean = false, val mirroring_note: String = "Imported pixels retained; no extra mirroring applied",
    val pixel_aspect: Float = 1f, val depth_manifest: String? = null,
    val inference_to_video: List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    val transform_note: String = "Upright inference normalized coordinates; rotation baked before inference; full decoded crop"
)
@Serializable data class Landmark(
    val x: Float, val y: Float, val visibility: Float?, val presence: Float?, val valid: Boolean,
    val normalized_z: Float? = null
)
@Serializable data class Person(val landmarks: List<Landmark>) {
    fun torso(): List<Landmark> = listOf(11, 12, 23, 24).mapNotNull { landmarks.getOrNull(it) }.filter { it.valid }
    fun center(): Point? = torso().takeIf { it.size >= 3 }?.let { Point(it.map { p -> p.x }.average().toFloat(), it.map { p -> p.y }.average().toFloat()) }
}
@Serializable data class PoseFrame(
    val timestamp_us: Long, val source_pts_us: Long, val people: List<Person>,
    val coordinate_space: String = SPACE, val validity: String = "observed",
    val confidence: Float? = null
)
@Serializable data class RawPose(
    val schema_version: Int = 1, val capture: Capture, val provenance: Provenance,
    val frames: List<PoseFrame>, val run_id: String = newId(),
    val coordinate_note: String = "Normalized z is model-relative depth, not a calibrated wall measurement"
)
@Serializable data class Hold(
    val id: String = newId(), val display_number: Int, val kind: String = "hold",
    val parts: List<List<Point>>, val parents: List<String> = emptyList(),
    val confidence: Float? = null, val validity: String = "user_corrected",
    val timestamp_us: Long, val coordinate_space: String = SPACE,
    val provenance: Provenance? = Provenance("local_user","none","not_applicable","manual_polygon_v1","none")
)
@Serializable data class WallVersion(
    val wall_id: String = newId(), val version_id: String = newId(),
    val parent_version_id: String? = null, val reference_frame_us: Long = 0,
    val holds: List<Hold> = emptyList()
)
@Serializable data class RouteVersion(
    val route_id: String = newId(), val version_id: String = newId(),
    val parent_version_id: String? = null, val wall_version_id: String,
    val members: Set<String> = emptySet(), val starts: Set<String> = emptySet(),
    val finishes: Set<String> = emptySet(), val type: String = "indoor_boulder",
    val rules: String = "Manual start and finish; no completion inference in M1"
)
@Serializable data class Seed(val timestamp_us: Long, val candidate_index: Int, val segment_id: String = newId())
@Serializable data class Correction(
    val id: String = newId(), val timestamp_us: Long, val created_epoch_ms: Long = System.currentTimeMillis(),
    val operation: String, val parent_version_id: String, val version_id: String,
    val affected_ids: List<String> = emptyList(), val author: String = "local_user"
)
@Serializable data class Session(
    val schema_version: Int = 1, val id: String, val name: String,
    val wall: WallVersion = WallVersion(), val route: RouteVersion,
    val seeds: List<Seed> = emptyList(), val corrections: List<Correction> = emptyList(),
    val show_static_holds: Boolean = true,
    val raw_hold_files: List<String> = emptyList(),
    val track_algorithm: String = "torso_distance_causal_v1"
)

data class TrackedFrame(val timestampUs: Long, val person: Person?, val segmentId: String?, val state: String)
