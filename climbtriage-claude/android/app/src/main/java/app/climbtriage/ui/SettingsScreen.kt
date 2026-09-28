package app.climbtriage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.climbtriage.container
import app.climbtriage.holds.BackendClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalContext.current.container.settings
    var url by remember { mutableStateOf(settings.backendUrl) }
    var token by remember { mutableStateOf(settings.backendToken ?: "") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Cloud hold detection is optional. The phone never holds model-provider keys; it talks to a ClimbTriage " +
            "backend you run. On a USB-connected phone use `adb reverse tcp:8000 tcp:8000` and http://localhost:8000.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Backend deployment token (optional)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { settings.backendUrl = url; settings.backendToken = token; onBack() }) { Text("Save") }
            OutlinedButton(onClick = {
                status = "checking…"
                scope.launch {
                    status = runCatching { BackendClient(url, token.ifBlank { null }).health().toString() }
                        .getOrElse { "unreachable: ${it.message}" }
                }
            }) { Text("Test connection") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
