package app.climbtriage.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.climbtriage.container
import app.climbtriage.contracts.AnalysisRun
import app.climbtriage.contracts.Correction
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.Ids
import app.climbtriage.contracts.Provenance
import app.climbtriage.geometry.Polygons
import app.climbtriage.geometry.Pt
import app.climbtriage.holds.BackendClient
import app.climbtriage.holds.ColorAssist
import app.climbtriage.holds.DisplayNumbering
import app.climbtriage.holds.HoldMap
import app.climbtriage.holds.HoldMapState
import app.climbtriage.holds.RouteSuggest
import app.climbtriage.media.FrameSource
import app.climbtriage.ui.Overlay.hold
import app.climbtriage.ui.Overlay.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant

private enum class Tool(val label: String) {
    SELECT("Select"), ADD("Add"), MOVE("Move"), SPLIT("Split"), MERGE("Merge"),
    ROUTE("Route ±"), START("Start"), FINISH("Finish"), COLOUR("Colour assist")
}

@Composable
fun HoldEditorScreen(id: String, back: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var wall by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf(HoldMapState(emptyList(), app.climbtriage.holds.RouteState())) }
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var selected by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<Pt>?>(null) }
    var status by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmCloud by remember { mutableStateOf(false) }
    var cloudJob by remember { mutableStateOf<Job?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(id) { wall = c.repository.wallImage(id) }
    LaunchedEffect(id, refresh) {
        state = c.repository.holdMap(id)
        version = c.repository.latestWall(id)?.version ?: 0
    }

    fun commit(build: suspend (seq: Int) -> Correction?) = scope.launch {
        val corr = build(c.repository.nextSeq(id)) ?: run { status = "That edit did not apply."; return@launch }
        c.repository.appendCorrection(id, corr)
        refresh++
    }

    fun holdAt(p: Pt): Hold? = state.live
        .map { it to Polygons.distance(Polygons.fromLists(it.polygon), p) }
        .filter { it.second < 0.015 }
        .minByOrNull { it.second }?.first

    val w = wall
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = back) { Text("Back") }
            Text("  Holds & route · saved v$version", style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t -> FilterChip(tool == t, { tool = t; preview = null }, { Text(t.label) }) }
        }
        if (w == null) { Text("No wall image yet — run the on-device analysis first."); return@Column }

        val numbers = DisplayNumbering.number(state.holds, state.route.members)
        val paint = remember { Paint().apply { color = android.graphics.Color.WHITE; textSize = 34f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK) } }
        FittedImage(
            w, w.width, w.height, Modifier.fillMaxWidth().weight(1f),
            onTap = { p ->
                val h = holdAt(p)
                when (tool) {
                    Tool.SELECT, Tool.MOVE, Tool.SPLIT -> selected = h?.id
                    Tool.ADD -> commit { seq -> HoldMap.addHold(seq, Polygons.box(p.x, p.y, 0.02, 0.02 * w.width / w.height)) }
                    Tool.MERGE -> {
                        val a = selected?.let { state.hold(it) }
                        if (a != null && h != null && h.id != a.id) commit { seq -> HoldMap.mergeHolds(seq, a, h) }.also { selected = null }
                        else selected = h?.id
                    }
                    Tool.ROUTE -> h?.let { hh -> commit { seq -> if (hh.id in state.route.members) HoldMap.removeMember(seq, hh.id) else HoldMap.addMember(seq, hh.id) } }
                    Tool.START -> h?.let { hh -> commit { seq -> HoldMap.setStart(seq, toggle(state.route.start, hh.id, HoldMap.MAX_START)) } }
                    Tool.FINISH -> h?.let { hh -> commit { seq -> HoldMap.setFinish(seq, toggle(state.route.finish, hh.id, HoldMap.MAX_FINISH)) } }
                    Tool.COLOUR -> scope.launch {
                        status = "Colour assist running on device…"
                        val holds = withContext(Dispatchers.Default) {
                            val small = FrameSource.scaled(w, 480)
                            val px = FrameSource.argb(small)
                            val assist = ColorAssist()
                            val runId = Ids.new("run")
                            runId to assist.propose(px, small.width, small.height, assist.seedColor(px, small.width, small.height, p), runId)
                        }
                        val run = AnalysisRun(id = holds.first, kind = "hold_candidates", inputSha256 = "wall.jpg", provider = "on-device",
                            model = "heuristic.colourassist", modelVersion = "colourassist.v1", status = "succeeded",
                            startedAt = Instant.now().toString(), finishedAt = Instant.now().toString(),
                            provenance = Provenance("heuristic", "heuristic.colourassist", "colourassist.v1", holds.first))
                        c.repository.addHoldRun(id, run, holds.second)
                        status = "Colour assist proposed ${holds.second.size} candidates (heuristic, unvalidated — review each)."
                        refresh++
                    }
                }
            },
            onDrag = { from, to, end ->
                when (tool) {
                    Tool.ADD -> {
                        val box = listOf(Pt(from.x, from.y), Pt(to.x, from.y), Pt(to.x, to.y), Pt(from.x, to.y))
                        if (end) { preview = null; if (Polygons.area(box) > 1e-5) commit { seq -> HoldMap.addHold(seq, Polygons.clampUnit(box)) } }
                        else preview = box
                    }
                    Tool.MOVE -> selected?.let { sid -> state.hold(sid) }?.let { h ->
                        val moved = Polygons.clampUnit(Polygons.translate(Polygons.fromLists(h.polygon), to.x - from.x, to.y - from.y))
                        if (end) { preview = null; commit { seq -> HoldMap.editHold(seq, h.id, moved) } } else preview = moved
                    }
                    Tool.SPLIT -> selected?.let { sid -> state.hold(sid) }?.let { h ->
                        if (end) { preview = null; commit { seq -> HoldMap.splitHold(seq, h, from, to) }; selected = null }
                        else preview = listOf(from, to)
                    }
                    else -> {}
                }
            },
        ) { toView ->
            for (h in state.live) {
                val color = when {
                    h.id in state.route.finish -> Overlay.finishColor
                    h.id in state.route.start -> Overlay.startColor
                    h.id in state.route.members -> Overlay.routeColor
                    h.provenance.kind == "heuristic" -> Overlay.heuristicColor
                    else -> Overlay.wallHoldColor
                }
                val isSel = h.id == selected
                hold(h, toView, if (isSel) Overlay.selectedColor else color, if (isSel) 6f else 3f,
                    fillAlpha = if (h.id in state.route.members) 0.25f else 0f, dashed = h.id !in state.route.members)
                numbers[h.id]?.let { label(h, toView, it.toString(), paint) }
            }
            preview?.let { pts ->
                if (pts.size == 2) drawLine(Overlay.selectedColor, pts[0].offset(toView), pts[1].offset(toView), 5f)
                else pts.zipWithNext().plus(pts.last() to pts.first()).forEach { (a, b) -> drawLine(Color.Cyan, a.offset(toView), b.offset(toView), 4f) }
            }
        }

        val sel = selected?.let { state.hold(it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = sel != null, onClick = { sel?.let { h -> commit { seq -> HoldMap.removeHold(seq, h.id) }; selected = null } }) { Text("Remove") }
            OutlinedButton(enabled = sel != null, onClick = { sel?.let { h -> commit { seq -> HoldMap.setKind(seq, h.id, if (h.kind == "volume") "hold" else "volume") } } }) {
                Text(if (sel?.kind == "volume") "Mark hold" else "Mark volume")
            }
            OutlinedButton(enabled = sel != null, onClick = {
                sel?.let { h -> val p = Polygons.fromLists(h.polygon); commit { seq -> HoldMap.editHold(seq, h.id, Polygons.clampUnit(Polygons.scaleAbout(p, Polygons.centroid(p), 1.15))) } }
            }) { Text("Grow") }
            OutlinedButton(enabled = sel != null, onClick = {
                sel?.let { h -> val p = Polygons.fromLists(h.polygon); commit { seq -> HoldMap.editHold(seq, h.id, Polygons.scaleAbout(p, Polygons.centroid(p), 0.87)) } }
            }) { Text("Shrink") }
            OutlinedButton(enabled = sel != null, onClick = {
                sel?.let { h ->
                    val ids = RouteSuggest.suggest(state.holds, h.id).filter { it !in state.route.members }
                    scope.launch {
                        for (hid in ids) c.repository.appendCorrection(id, HoldMap.addMember(c.repository.nextSeq(id), hid))
                        status = "Added ${ids.size} same-colour holds to the route — review neighbours of the same colour."
                        refresh++
                    }
                }
            }) { Text("Suggest route by colour") }
            Button(onClick = { confirmCloud = true }, enabled = cloudJob == null) { Text("Detect holds (cloud)") }
            OutlinedButton(onClick = { scope.launch { status = if (c.repository.undoLast(id)) "Undone." else "Nothing unsaved to undo."; refresh++ } }) { Text("Undo") }
            Button(onClick = { scope.launch { val v = c.repository.saveVersion(id); status = "Saved wall v${v.version}."; refresh++ } }) { Text("Save version") }
        }
        val fixtures = state.live.count { it.provenance.isFixture }
        Text(buildString {
            append("${state.live.size} holds · ${state.route.members.size} on route · start ${state.route.start.size} · finish ${state.route.finish.size}")
            if (state.route.finish.isEmpty()) append(" · finish not set (never inferred from the highest hold)")
            if (fixtures > 0) append(" · ⚠ $fixtures FIXTURE holds (test provider, not real inference)")
        }, style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    if (confirmCloud) AlertDialog(
        onDismissRequest = { confirmCloud = false },
        title = { Text("Upload the wall image?") },
        text = { Text("One climber-free wall image (no video) is sent to your ClimbTriage backend at ${c.settings.backendUrl}, " +
            "which runs SAM 3.1 through its configured provider. Nothing else leaves the phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmCloud = false
                val bmp = wall ?: return@TextButton
                cloudJob = scope.launch {
                    runCatching {
                        val jpeg = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray() }
                        val result = BackendClient(c.settings.backendUrl, c.settings.backendToken).detectHolds(jpeg) { s ->
                            status = "Backend: ${s.status} (${s.step}/${s.stepsTotal} ${s.stepName ?: ""})"
                        }
                        c.repository.addHoldRun(id, result.run, result.holds)
                        status = "Received ${result.holds.size} hold candidates from ${result.run.model}" +
                            if (result.run.provenance.isFixture) " — ⚠ FIXTURE provider, not real inference" else ""
                        refresh++
                    }.onFailure { status = "Cloud detection failed: ${it.message}" }
                    cloudJob = null
                }
            }) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = { confirmCloud = false }) { Text("Cancel") } },
    )
}

private fun toggle(list: List<String>, id: String, max: Int): List<String> =
    if (id in list) list - id else (list + id).takeLast(max)
