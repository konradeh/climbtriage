package app.climbtriage.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

sealed interface Screen {
    data object Sessions : Screen
    data object Record : Screen
    data object Settings : Screen
    data class Session(val id: String) : Screen
    data class SelectPerson(val id: String) : Screen
    data class Holds(val id: String) : Screen
    data class Replay(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Sessions) }
    val top by remember { androidx.compose.runtime.derivedStateOf { stack.last() } }
    val go: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    BackHandler(enabled = stack.size > 1, onBack = back)
    when (val s = top) {
        Screen.Sessions -> SessionListScreen(go)
        Screen.Record -> RecordScreen(onRecorded = { id -> back(); go(Screen.Session(id)) }, onBack = back)
        Screen.Settings -> SettingsScreen(back)
        is Screen.Session -> SessionScreen(s.id, go, back)
        is Screen.SelectPerson -> SelectPersonScreen(s.id, back)
        is Screen.Holds -> HoldEditorScreen(s.id, back)
        is Screen.Replay -> ReplayScreen(s.id, back)
    }
}
