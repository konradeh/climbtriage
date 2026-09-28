package app.climbtriage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.climbtriage.analysis.SessionAnalyzer
import app.climbtriage.container
import app.climbtriage.contracts.AnalysisRun
import app.climbtriage.contracts.Capture
import app.climbtriage.contracts.PersonTrack
import app.climbtriage.contracts.Validity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(id: String, go: (Screen) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<Capture?>(null) }
    var poseRun by remember { mutableStateOf<AnalysisRun?>(null) }
    var track by remember { mutableStateOf<PersonTrack?>(null) }
    var hasWall by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SessionAnalyzer.Progress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(id, refresh) {
        capture = c.repository.capture(id)
        poseRun = c.repository.runs(id, "pose_detection").lastOrNull()
        track = c.repository.track(id)
        hasWall = c.repository.wallImageFile(id).exists()
    }
    val cap = capture ?: return

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = back) { Text("Back") }
            Text("  Session", style = MaterialTheme.typography.headlineSmall)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val v = cap.video
                Text("${v.displayWidthPx}×${v.displayHeightPx} · rotation ${v.rotationDeg}° · %.2f s · %d frames (PTS)".format(
                    v.durationUs / 1e6, cap.timebase.ptsUs.size))
                v.nominalFps?.let { Text("nominal %.1f fps (informational; timing uses PTS)".format(it), style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Source is mirrored (front camera)")
                    Switch(v.mirrored, onCheckedChange = { m ->
                        scope.launch { c.repository.updateCapture(cap.copy(video = v.copy(mirrored = m))); refresh++ }
                    }, enabled = poseRun == null)
                }
                cap.stillness?.let { s ->
                    val moved = s.verdict == "MOVED"
                    Text(if (moved) "⚠ Camera moved in ${s.movedFrameTimesUs.size} sampled frames — overlays are suppressed near them. " +
                        "M1 supports a stationary camera only." else "Camera stillness verified (${s.method}).",
                        color = if (moved) Color(0xFFFFAB40) else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        StepCard("1 · On-device analysis", "MediaPipe pose at 10 Hz on real timestamps, plus a climber-free wall image. Nothing is uploaded.") {
            val p = progress
            if (p != null) {
                LinearProgressIndicator(progress = { p.done.toFloat() / maxOf(1, p.total) }, modifier = Modifier.fillMaxWidth())
                Text("${p.stage}: ${p.done}/${p.total} frames")
                OutlinedButton(onClick = { job?.cancel(); progress = null }) { Text("Cancel") }
            } else {
                poseRun?.coverage?.let { cov ->
                    Text("Analysed ${cov.framesAnalyzed}/${cov.framesRequested} frames (${cov.framesDropped} dropped) · ${poseRun!!.model}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    job = scope.launch {
                        runCatching { c.analyzer.analyze(id) { progress = it } }
                            .onFailure { message = "Analysis failed: ${it.message}" }
                        progress = null
                        refresh++
                    }
                }) { Text(if (poseRun == null) "Analyse clip" else "Re-analyse") }
            }
        }

        StepCard("2 · Select the climber", "Tap the climber once. The track is followed from there; ambiguity and loss are marked, never guessed.") {
            track?.let { t ->
                val valid = t.samples.count { it.validity == Validity.VALID || it.validity == Validity.LOW_CONFIDENCE }
                Text("Tracked in $valid/${t.samples.size} samples · ${t.lostIntervals.size} lost/ambiguous intervals",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = poseRun != null, onClick = { go(Screen.SelectPerson(id)) }) { Text(if (track == null) "Select climber" else "Reselect") }
        }

        StepCard("3 · Holds & route", "Detect or draw holds on the wall image, then mark which ones are the route and its start/finish.") {
            Button(enabled = hasWall, onClick = { go(Screen.Holds(id)) }) { Text("Edit holds & route") }
        }

        StepCard("4 · Replay", "Video with the climber's skeleton and the route aligned on real timestamps.") {
            Button(enabled = track != null || hasWall, onClick = { go(Screen.Replay(id)) }) { Text("Replay") }
        }

        OutlinedButton(onClick = {
            scope.launch { message = runCatching { "Exported ${c.repository.exportDocument(id).absolutePath}" }.getOrElse { "Export failed: ${it.message}" } }
        }) { Text("Export session JSON (climbtriage.v1)") }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StepCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}
