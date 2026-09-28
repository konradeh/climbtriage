package app.climbtriage.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.climbtriage.container
import kotlinx.coroutines.launch

/**
 * CameraX recording to app-private storage, back camera. No inference runs here: recording is
 * independent of analysis by construction (live overlay is M4).
 */
@SuppressLint("MissingPermission")
@Composable
fun RecordScreen(onRecorded: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    val capture = remember {
        VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build())
    }
    val previewView = remember { PreviewView(context) }

    if (granted) {
        DisposableEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }, ContextCompat.getMainExecutor(context))
            onDispose { runCatching { future.get().unbindAll() } }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
        else Text("Camera permission is needed to record. You can still import clips.", Modifier.padding(24.dp))
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Put the phone on a stand with the whole route in frame. Keep it still.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, enabled = recording == null) { Text("Back") }
                if (recording == null) {
                    Button(enabled = granted, onClick = {
                        val (id, file) = repo.newRecordingTarget()
                        val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                        val withAudio = if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED) pending.withAudioEnabled() else pending
                        recording = withAudio.start(ContextCompat.getMainExecutor(context)) { event ->
                            if (event is VideoRecordEvent.Finalize) {
                                recording = null
                                if (event.hasError()) {
                                    Toast.makeText(context, "Recording failed (${event.error})", Toast.LENGTH_LONG).show()
                                } else scope.launch {
                                    runCatching { repo.registerRecording(id) }
                                        .onSuccess(onRecorded)
                                        .onFailure { Toast.makeText(context, "Could not read recording: ${it.message}", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }
                    }) { Text("● Record") }
                } else {
                    Button(onClick = { recording?.stop() }) { Text("■ Stop") }
                }
            }
        }
    }
}
