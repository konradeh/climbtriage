package app.climbtriage.perception

import android.content.Context
import android.graphics.Bitmap
import app.climbtriage.domain.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable
import java.security.MessageDigest

class PoseEngine(context: Context): Closeable {
    val provenance: Provenance
    private val landmarker: PoseLandmarker
    init {
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            context.assets.open("pose_landmarker_lite.task").use { input ->
                val buffer=ByteArray(64*1024)
                while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
            }
        } catch(error: Exception) {
            throw IllegalStateException("Pose model missing. Run scripts/fetch-model.ps1 and rebuild; manual review stays available.",error)
        }
        val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val config="poses=4;visibility=.6;presence=.6;sample_period_us=66667"
        val configHash=MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        provenance=Provenance("mediapipe", "pose_landmarker_lite", hash,
            "tasks-vision-0.10.21/cpu/video", configHash)
        landmarker=PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumPoses(4)
            .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f).build())
    }
    fun detect(bitmap: Bitmap, timeUs: Long, sourcePtsUs: Long): PoseFrame {
        val image=BitmapImageBuilder(bitmap).build()
        try {
            val result=landmarker.detectForVideo(image,timeUs/1000)
            return PoseFrame(timestamp_us=timeUs,source_pts_us=sourcePtsUs,people=result.landmarks().map { body ->
                Person(body.map { p ->
                    val visibility=p.visibility().orElse(null)
                    val presence=p.presence().orElse(null)
                    Landmark(p.x(),p.y(),visibility,presence,
                        p.x().isFinite() && p.y().isFinite() && p.x() in 0f..1f && p.y() in 0f..1f &&
                            (visibility ?: 0f)>=.6f && (presence ?: 0f)>=.6f,
                        normalized_z=p.z().takeIf { it.isFinite() })
                })
            })
        } finally { image.close() }
    }
    override fun close() = landmarker.close()
}
