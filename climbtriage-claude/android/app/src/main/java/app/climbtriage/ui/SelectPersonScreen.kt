package app.climbtriage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.climbtriage.container
import app.climbtriage.contracts.Capture
import app.climbtriage.contracts.PoseSample
import app.climbtriage.contracts.Validity
import app.climbtriage.media.FrameSource
import app.climbtriage.perception.DetectionRun
import app.climbtriage.ui.Overlay.skeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SelectPersonScreen(id: String, back: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<Capture?>(null) }
    var detections by remember { mutableStateOf<DetectionRun?>(null) }
    var position by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        capture = c.repository.capture(id)
        detections = c.repository.detections(id)
        // Start on the first frame that has anyone in it.
        detections?.frames?.indexOfFirst { it.people.isNotEmpty() }?.takeIf { it >= 0 }?.let { position = it.toFloat() }
    }
    val det = detections
    val cap = capture
    if (det == null || cap == null || det.frames.isEmpty()) {
        Text("Loading…", Modifier.padding(24.dp)); return
    }
    val frameIdx = position.toInt().coerceIn(0, det.frames.lastIndex)
    val frame = det.frames[frameIdx]
    LaunchedEffect(frame.frameIndex) {
        bitmap = withContext(Dispatchers.IO) {
            FrameSource(c.repository.mediaFile(id), cap.video).use { it.frame(frame.frameIndex, 1280) }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = back) { Text("Back") }
            Text("  Tap the climber", style = MaterialTheme.typography.titleMedium)
        }
        Text("t = %.2f s · %d people detected in this frame".format(frame.tUs / 1e6, frame.people.size) +
            if (!frame.analyzed) " · frame not analysed" else "", style = MaterialTheme.typography.bodySmall)
        FittedImage(
            bitmap, cap.video.displayWidthPx ?: 1, cap.video.displayHeightPx ?: 1,
            Modifier.fillMaxWidth().weight(1f),
            onTap = { p ->
                if (busy) return@FittedImage
                val hit = frame.people.indexOfFirst { b -> p.x in b.bbox[0]..(b.bbox[0] + b.bbox[2]) && p.y in b.bbox[1]..(b.bbox[1] + b.bbox[3]) }
                if (hit >= 0) {
                    busy = true
                    scope.launch { c.analyzer.trackFrom(id, frameIdx, hit); busy = false; back() }
                }
            },
        ) { toView ->
            frame.people.forEachIndexed { i, person ->
                val tl = toView.apply(person.bbox[0], person.bbox[1])
                val br = toView.apply(person.bbox[0] + person.bbox[2], person.bbox[1] + person.bbox[3])
                drawRect(Overlay.selectedColor, Offset(tl.x.toFloat(), tl.y.toFloat()),
                    Size((br.x - tl.x).toFloat(), (br.y - tl.y).toFloat()), style = Stroke(4f))
                skeleton(PoseSample(frame.tUs, frame.frameIndex, Validity.VALID, landmarks = person.landmarks), toView,
                    Color.hsv((i * 120f) % 360f, 0.8f, 1f))
            }
        }
        Slider(position, { position = it }, valueRange = 0f..det.frames.lastIndex.toFloat().coerceAtLeast(1f), steps = 0)
        if (busy) Text("Tracking from this frame…")
    }
}
