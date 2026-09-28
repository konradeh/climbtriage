package app.climbtriage.perception

import android.content.Context
import android.graphics.Bitmap
import app.climbtriage.contracts.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable

/** Replaceable pose backend. Implementations return people without identity. */
interface PoseEngine : Closeable {
    val model: String
    val modelVersion: String
    fun detect(frame: Bitmap): List<PersonDetection>
}

/**
 * MediaPipe Pose Landmarker (BlazePose GHUM 3D, Apache-2.0), IMAGE mode.
 *
 * IMAGE mode is stateless: every frame is detected independently, so stored detections can be
 * re-associated deterministically by PersonTracker. The model card's intended use is single-person
 * video; climbing performance is unmeasured until docs/07 is run.
 */
class MediaPipePoseEngine(context: Context, maxPeople: Int = 3) : PoseEngine {
    override val model = "mediapipe/pose_landmarker_full"
    override val modelVersion = "float16/latest@tasks-vision-0.10.35"

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(maxPeople)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .build(),
    )

    override fun detect(frame: Bitmap): List<PersonDetection> {
        val result = landmarker.detect(BitmapImageBuilder(frame).build())
        return result.landmarks().mapNotNull { person ->
            val lms = person.mapIndexed { i, lm ->
                Landmark(
                    name = MEDIAPIPE33.getOrElse(i) { "lm$i" },
                    x = lm.x().toDouble(), y = lm.y().toDouble(), z = lm.z().toDouble(),
                    visibility = lm.visibility().orElse(null)?.toDouble(),
                    presence = lm.presence().orElse(null)?.toDouble(),
                )
            }
            bboxOf(lms)?.let { PersonDetection(it, lms) }
        }
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "pose_landmarker_full.task"
    }
}
