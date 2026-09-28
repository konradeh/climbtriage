package app.climbtriage.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Kotlin mirror of contracts/schema/climbtriage.v1.schema.json. The schema is the source of
 * truth; ContractsGoldenTest decodes the shared golden example with these types.
 */
const val SCHEMA_V1 = "climbtriage.v1"

val ContractJson = Json {
    ignoreUnknownKeys = true      // additive optional fields keep the same major version
    explicitNulls = true
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
enum class CoordinateSystem {
    @SerialName("video_px") VIDEO_PX,
    @SerialName("frame_norm") FRAME_NORM,
    @SerialName("inference_norm") INFERENCE_NORM,
    @SerialName("wall_norm") WALL_NORM,
}

@Serializable
enum class Validity { VALID, LOW_CONFIDENCE, AMBIGUOUS, LOST, NOT_ANALYZED }

@Serializable
data class Provenance(
    val kind: String,              // model | heuristic | user | fixture
    val name: String,
    val version: String,
    val runId: String? = null,
    val configHash: String? = null,
) {
    val isFixture: Boolean get() = kind == "fixture"
}

@Serializable
data class VideoInfo(
    val widthPx: Int,
    val heightPx: Int,
    val rotationDeg: Int,
    val mirrored: Boolean,
    val displayWidthPx: Int? = null,
    val displayHeightPx: Int? = null,
    val durationUs: Long,
    val codec: String? = null,
    val nominalFps: Double? = null,     // informational only; never used for timing
)

@Serializable
data class Timebase(val kind: String = "media_pts", val ptsUs: List<Long>)

@Serializable
data class DeviceInfo(val manufacturer: String, val model: String, val sdkInt: Int)

@Serializable
data class Stillness(
    val verdict: String,                // STILL | MOVED | UNKNOWN
    val maxShiftNorm: Double,
    val movedFrameTimesUs: List<Long> = emptyList(),
    val method: String,
)

@Serializable
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double)

@Serializable
data class Calibration(val validated: Boolean, val method: String)

@Serializable
data class Capture(
    val id: String,
    val createdAt: String,
    val source: String,                 // recorded | imported
    val mediaSha256: String,
    val video: VideoInfo,
    val timebase: Timebase,
    val device: DeviceInfo? = null,
    val cameraFacing: String = "unknown",
    val stationaryClaim: Boolean = true,
    val stillness: Stillness? = null,
    val depth: JsonObject? = null,      // reserved (M5)
    val intrinsics: Intrinsics? = null,
    val calibration: Calibration? = null,
)

@Serializable
data class HoldColor(val lab: List<Double>, val hex: String, val method: String)

@Serializable
data class Hold(
    val id: String,
    val kind: String,                   // hold | volume | unknown
    val coords: CoordinateSystem,
    val polygon: List<List<Double>>,
    val bbox: List<Double>? = null,
    val color: HoldColor? = null,
    val confidence: Double? = null,
    val provenance: Provenance,
    val parents: List<String> = emptyList(),
    val retired: Boolean = false,
)

@Serializable
data class WallVersion(
    val id: String,
    val wallId: String,
    val version: Int,
    val parentVersionId: String? = null,
    val reason: String = "initial",
    val createdAt: String? = null,
    val coords: CoordinateSystem = CoordinateSystem.WALL_NORM,
    val referenceCaptureId: String? = null,
    val holds: List<Hold>,
    val sourceRunIds: List<String> = emptyList(),
    val correctionSeq: Int = 0,
)

@Serializable
data class RouteRules(
    val startRule: String = "hands_on_start_feet_off_floor",
    val finishRule: String = "matched_finish_dwell",
)

@Serializable
data class RouteVersion(
    val id: String,
    val routeId: String,
    val version: Int,
    val wallVersionId: String,
    val name: String? = null,
    val grade: String? = null,
    val colorHint: String? = null,
    val routeType: String = "boulder",
    val members: List<String>,
    val startHoldIds: List<String>,
    val finishHoldIds: List<String>,
    val displayNumbers: Map<String, Int> = emptyMap(),
    val rules: RouteRules = RouteRules(),
)

@Serializable
data class Landmark(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double? = null,
    val visibility: Double? = null,
    val presence: Double? = null,
)

@Serializable
data class PoseSample(
    val tUs: Long,
    val frameIndex: Int,
    val validity: Validity,
    val coords: CoordinateSystem = CoordinateSystem.FRAME_NORM,
    val skeleton: String = "mediapipe33",
    val landmarks: List<Landmark>,
    val bbox: List<Double>? = null,
    val associationCost: Double? = null,
)

@Serializable
data class Seed(val tUs: Long, val detectionIndex: Int, val selectedBy: String = "user")

@Serializable
data class LostInterval(val startUs: Long, val endUs: Long, val reason: String)

@Serializable
data class PersonTrack(
    val id: String,
    val captureId: String,
    val seed: Seed,
    val samples: List<PoseSample>,
    val lostIntervals: List<LostInterval> = emptyList(),
    val provenance: Provenance,
)

@Serializable
data class Coverage(val framesRequested: Int, val framesAnalyzed: Int, val framesDropped: Int)

@Serializable
data class AnalysisRun(
    val id: String,
    val kind: String,
    val inputSha256: String,
    val provider: String,
    val model: String,
    val modelVersion: String? = null,
    val configHash: String? = null,
    val schema: String = SCHEMA_V1,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val status: String,
    val coverage: Coverage? = null,
    val outputRef: String? = null,
    val provenance: Provenance,
)

@Serializable
data class Correction(
    val id: String,
    val seq: Int,
    val createdAt: String,
    val author: String = "local-user",
    val target: String,                 // wall | route | track
    val op: String,
    val payload: JsonObject,
)

/** M2 — declared so later modules share one vocabulary. Not produced in M1. */
@Serializable
data class ContactEvent(
    val id: String,
    val personTrackId: String,
    val limb: String,
    val target: JsonObject,
    val startUs: Long,
    val endUs: Long,
    val state: String,                  // CONTACT | UNKNOWN
    val footPoint: String? = null,      // toe_heel | ankle_approx | n/a
    val evidence: JsonObject? = null,
    val confidence: Double? = null,
    val provenance: Provenance,
)

@Serializable
data class Attempt(
    val id: String,
    val captureId: String,
    val routeVersionId: String,
    val startUs: Long? = null,
    val endUs: Long? = null,
    val boundarySource: String? = null,
    val outcome: String = "unknown",    // tracking loss is never "fell"
)

/** A whole session as one exportable document. */
@Serializable
data class SessionDocument(
    val schema: String = SCHEMA_V1,
    val capture: Capture? = null,
    val wallVersions: List<WallVersion> = emptyList(),
    val routeVersions: List<RouteVersion> = emptyList(),
    val personTracks: List<PersonTrack> = emptyList(),
    val analysisRuns: List<AnalysisRun> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val contactEvents: List<ContactEvent> = emptyList(),
    val attempts: List<Attempt> = emptyList(),
) {
    init {
        require(schema == SCHEMA_V1) { "unsupported schema $schema" }
    }
}
