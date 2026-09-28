package app.climbtriage.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.climbtriage.container
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionListScreen(go: (Screen) -> Unit) {
    val context = LocalContext.current
    val repo = context.container.repository
    val sessions by repo.sessions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { repo.importVideo(uri) }
                    .onSuccess { go(Screen.Session(it)) }
                    .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
                importing = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ClimbTriage", style = MaterialTheme.typography.headlineMedium)
        Text("Indoor bouldering · one climber · phone on a stand", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { go(Screen.Record) }) { Text("Record") }
            Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !importing) { Text("Import video") }
            OutlinedButton(onClick = { go(Screen.Settings) }) { Text("Settings") }
        }
        if (importing) Row { CircularProgressIndicator(); Text("  Copying clip into app storage…") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { go(Screen.Session(s.id)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance().format(Date(s.createdAt)) + " · %.1f s".format(s.durationUs / 1e6))
                        Text(listOfNotNull(
                            if (s.hasDetections) "pose ✓" else "pose –",
                            if (s.hasTrack) "climber ✓" else "climber –",
                            if (s.latestWallVersion > 0) "holds v${s.latestWallVersion} (${s.holdCount})" else "holds –",
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { repo.delete(s.id) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
