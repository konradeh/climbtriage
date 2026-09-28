package app.climbtriage

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.climbtriage.domain.*
import kotlinx.coroutines.delay
import kotlin.math.hypot

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xff6ee7cb),secondary=Color(0xffffc879),
                background=Color(0xff101c23),surface=Color(0xff192a34))) { App() }
        }
    }
}

@Composable fun App(vm:ClimbViewModel=viewModel()) {
    val review by vm.review.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importClip) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("CLIMBTRIAGE",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Text(if(review==null) "Your climbing sessions" else review!!.session.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("Stationary camera · local review",style=MaterialTheme.typography.bodySmall)
            if(review==null) {
                Button(onClick={ picker.launch(arrayOf("video/*")) },enabled=!busy) { Text("Import climbing clip") }
                Text("Up to 2 minutes / 300 MiB, SDR. Pose runs on this phone. Automatic holds require an explicit wall-frame upload; manual holds work offline.")
                library.forEach { item ->
                    OutlinedButton(onClick={vm.open(item)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(item.name) }
                }
                if(library.isEmpty()) Text("No saved sessions yet",color=MaterialTheme.colorScheme.secondary)
            } else ReviewScreen(review!!,vm,busy)
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick=vm::cancel) { Text("Cancel analysis") }
            }
            Text(status,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun ReviewScreen(review:Review,vm:ClimbViewModel,busy:Boolean) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val session=review.session
    val player=remember(session.id) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(vm.store.media(session.id)))); prepare()
    } }
    DisposableEffect(player,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.release() }
    }
    var position by remember(session.id) { mutableLongStateOf(0) }
    var playing by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(player) { while(true) { position=player.currentPosition.coerceAtLeast(0)*1000; playing=player.isPlaying; delay(33) } }
    var mode by remember(session.id) { mutableStateOf("Person") }
    var selected by remember(session.id) { mutableStateOf<Set<String>>(emptySet()) }
    var drawing by remember(session.id) { mutableStateOf<List<Point>>(emptyList()) }
    var firstSplit by remember(session.id) { mutableStateOf<List<Point>?>(null) }
    var localMessage by remember(session.id) { mutableStateOf("") }
    var uploadDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("http://127.0.0.1:8000") }
    val capture=review.raw.capture
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val raw=review.raw.frames.lastOrNull { it.timestamp_us<=position }?.takeIf { position-it.timestamp_us<=150_000 }
    val tracked=Tracking.at(review.track,position)
    val active=session.wall.holds.firstOrNull { it.id in selected }

    Box(Modifier.fillMaxWidth().height(330.dp).background(Color.Black)) {
        AndroidView(factory={ PlayerView(it).apply { this.player=player; useController=false; resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            modifier=Modifier.fillMaxSize(),update={ it.player=player })
        Canvas(Modifier.fillMaxSize().pointerInput(mode,session.wall,selected,raw,busy,playing) {
            detectTapGestures { tap ->
                if(busy || playing) return@detectTapGestures
                val fit=Fit.within(size.width.toFloat(),size.height.toFloat(),capture.width,capture.height)
                val p=fit.normalized(Point(tap.x,tap.y)) ?: return@detectTapGestures
                when(mode) {
                    "Person" -> {
                        val frame=raw
                        val nearest=frame?.people?.mapIndexedNotNull { i,person -> person.center()?.let {
                            i to hypot((it.x-p.x)*capture.width/capture.height.toFloat(),it.y-p.y)
                        } }?.minByOrNull { it.second }
                        if(frame!=null && nearest!=null && nearest.second<.3f) vm.select(frame.timestamp_us,nearest.first)
                        else localMessage="No reliable visible torso here. Seek to a clearer frame."
                    }
                    "Add", "Reshape", "Split" -> drawing=drawing+p
                    else -> {
                        val hit=session.wall.holds.lastOrNull { h -> h.parts.any { Geometry.contains(it,p) } }
                        selected=if(hit==null) emptySet() else if(mode=="Merge") {
                            if(hit.id in selected) selected-hit.id else (selected+hit.id).toList().takeLast(2).toSet()
                        } else setOf(hit.id)
                    }
                }
            }
        }) {
            val fit=Fit.within(size.width,size.height,capture.width,capture.height)
            fun at(p:Point):Offset { val q=fit.screen(p); return Offset(q.x,q.y) }
            fun ring(points:List<Point>,color:Color,width:Float,closed:Boolean=true) {
                if(points.isEmpty()) return
                val path=Path().apply { val p=at(points.first()); moveTo(p.x,p.y); points.drop(1).forEach { val q=at(it); lineTo(q.x,q.y) }; if(closed) close() }
                drawPath(path,color,style=Stroke(width))
            }
            val paint=android.graphics.Paint().apply { textSize=26f; isAntiAlias=true; color=android.graphics.Color.WHITE; setShadowLayer(3f,1f,1f,android.graphics.Color.BLACK) }
            if(session.show_static_holds) session.wall.holds.forEach { h ->
                val color=when { h.id in selected -> Color.White; h.id in suggestions -> Color(0xff68b7ff); h.id in session.route.members -> Color(0xff6ee7cb); else -> Color(0xffffc879) }
                h.parts.forEach { ring(it,color,if(h.id in selected) 5f else 3f) }
                val p=h.parts.firstOrNull()?.firstOrNull()?.let(::at)
                if(p!=null) {
                    val label="${h.display_number}"+(if(h.id in session.route.starts) " S" else "")+(if(h.id in session.route.finishes) " F" else "")
                    drawContext.canvas.nativeCanvas.drawText(label,p.x,p.y-5,paint)
                }
            }
            fun skeleton(person:Person,color:Color) {
                val edges=listOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,29 to 31,28 to 30,30 to 32)
                edges.forEach { (a,b) ->
                    val x=person.landmarks.getOrNull(a);val y=person.landmarks.getOrNull(b)
                    if(x?.valid==true && y?.valid==true) drawLine(color,at(Point(x.x,x.y)),at(Point(y.x,y.y)),4f)
                }
                person.landmarks.filter { it.valid }.forEach { drawCircle(color,4f,at(Point(it.x,it.y))) }
            }
            if(mode=="Person") raw?.people?.forEachIndexed { index,p ->
                skeleton(p,Color(0xffd2c9ff)); p.center()?.let { val q=at(it); drawContext.canvas.nativeCanvas.drawText("Person ${index+1}",q.x,q.y,paint) }
            } else tracked?.person?.let { skeleton(it,Color(0xffb5a3ff)) }
            firstSplit?.let { ring(it,Color.Cyan,4f) }
            ring(drawing,Color.White,4f,false)
            drawing.forEach { drawCircle(Color.White,5f,at(it)) }
        }
    }
    Text(tracked?.state ?: "No current pose sample",color=MaterialTheme.colorScheme.secondary)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ if(playing) player.pause() else player.play() },enabled=!busy) { Text(if(playing) "Pause" else "Play") }
        Text("%.2f / %.2f s".format(position/1e6,capture.duration_us/1e6),modifier=Modifier.padding(top=12.dp))
    }
    Slider(value=(position.toFloat()/capture.duration_us).coerceIn(0f,1f),onValueChange={ player.pause(); player.seekTo((it*capture.duration_us/1000).toLong()) },enabled=!busy)
    Text("Pause to select or edit. Green outlines = route membership, not detected contact.",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("Person","Hold","Add","Reshape","Split","Merge").forEach { name ->
            FilterChip(selected=mode==name,onClick={ mode=name; drawing=emptyList(); firstSplit=null; player.pause() },label={Text(name)},enabled=!busy)
        }
    }
    if(mode in listOf("Add","Reshape","Split")) {
        Text(when(mode) { "Add" -> "Tap at least 3 points around the new hold."; "Reshape" -> "Select a hold in Hold mode first, then draw its replacement outline."; else -> "Select one parent hold first. Draw the two child outlines in sequence; start/finish must be reconfirmed." },style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={drawing=drawing.dropLast(1)},enabled=drawing.isNotEmpty()&&!busy) { Text("Undo point") }
            Button(onClick={
                if(!Geometry.valid(drawing)) { localMessage="Outline must be a simple nonempty polygon"; return@Button }
                if(mode!="Add" && active==null) { localMessage="Choose one hold in Hold mode first"; return@Button }
                if(mode=="Split" && firstSplit==null) { firstSplit=drawing; drawing=emptyList(); localMessage="Now draw the second child outline" }
                else {
                    val points=drawing; val first=firstSplit; val parent=active; val operation=mode
                    vm.edit { s ->
                        val max=s.wall.holds.maxOfOrNull { it.display_number } ?: 0
                        val children=when(operation) {
                            "Add" -> listOf(Hold(display_number=max+1,parts=listOf(points),timestamp_us=position))
                            "Reshape" -> listOf(parent!!.copy(parts=listOf(points),validity="user_corrected",confidence=null))
                            else -> listOf(Hold(display_number=max+1,parts=listOf(first!!),parents=listOf(parent!!.id),timestamp_us=position),
                                Hold(display_number=max+2,parts=listOf(points),parents=listOf(parent.id),timestamp_us=position))
                        }
                        val holds=(if(operation=="Add") s.wall.holds else s.wall.holds.filter { it.id!=parent!!.id })+children
                        val next=Editor.change(s,holds,operation.lowercase(),listOfNotNull(parent?.id)+children.map { it.id },position)
                        if(operation=="Split" && parent!!.id in s.route.members) next.copy(route=next.route.copy(members=next.route.members+children.map { it.id })) else next
                    }
                    drawing=emptyList(); firstSplit=null; mode="Hold"; selected=emptySet()
                }
            },enabled=!busy && drawing.size>=3) { Text(if(mode=="Split" && firstSplit==null) "Save first outline" else "Apply outline") }
        }
    }
    if(mode=="Merge") Button(onClick={
        val ids=selected
        vm.edit { s ->
            val parents=s.wall.holds.filter { it.id in ids }; require(parents.size==2)
            val hold=Hold(display_number=(s.wall.holds.maxOfOrNull { it.display_number }?:0)+1,parts=parents.flatMap { it.parts },parents=ids.toList(),timestamp_us=position)
            val next=Editor.change(s,s.wall.holds.filter { it.id !in ids }+hold,"merge",ids.toList()+hold.id,position)
            if(ids.any { it in s.route.members }) next.copy(route=next.route.copy(members=next.route.members+hold.id)) else next
        }; selected=emptySet()
    },enabled=selected.size==2&&!busy) { Text("Merge two selected holds") }
    if(active!=null && mode=="Hold") {
        Text("Hold ${active.display_number} · ${active.kind} · ${active.validity}"+(active.confidence?.let { " · model score %.2f".format(it) } ?: ""))
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick={vm.suggestRoute(active.id)},enabled=!busy) { Text("Suggest route") }
            listOf("member" to "Route", "start" to "Start", "finish" to "Finish").forEach { (key,label) ->
                OutlinedButton(onClick={vm.edit { Editor.route(it,active.id,key,position) }},enabled=!busy) { Text(label) }
            }
            OutlinedButton(onClick={vm.edit { s -> Editor.change(s,s.wall.holds.map { if(it.id==active.id) it.copy(kind=if(it.kind=="hold") "volume" else "hold") else it },"hold_kind",listOf(active.id),position) }},enabled=!busy) { Text("Hold / volume") }
            OutlinedButton(onClick={vm.edit { s -> Editor.change(s,s.wall.holds.filter { it.id!=active.id },"remove",listOf(active.id),position) }; selected=emptySet()},enabled=!busy) { Text("Remove") }
        }
    }
    if(suggestions.isNotEmpty()) {
        Text("${suggestions.size} blue suggestions. Same-color neighboring routes may be included.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=vm::acceptSuggestions,enabled=!busy) { Text("Accept suggestions") }
            OutlinedButton(onClick={vm.suggestions.value=emptySet()},enabled=!busy) { Text("Dismiss") }
        }
    }
    Text("${session.wall.holds.size} wall objects · ${session.route.members.size} route members · ${session.route.starts.size} start / ${session.route.finishes.size} finish",style=MaterialTheme.typography.bodySmall)
    val known=review.track.count { it.person!=null }
    Text("Pose samples: ${capture.analyzed_frames}/${capture.decoded_frames} decoded frames. Selected identity coverage: $known/${review.track.size} analyzed samples. Quality unvalidated.",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Checkbox(checked=session.show_static_holds,onCheckedChange={ show -> vm.edit { it.copy(show_static_holds=show) } },enabled=!busy)
        Text("Show static hold map. Turn off if the camera moved.",modifier=Modifier.padding(top=10.dp),style=MaterialTheme.typography.bodySmall)
    }
    if(localMessage.isNotEmpty()) Text(localMessage,color=MaterialTheme.colorScheme.secondary)
    Button(onClick={uploadDialog=true},enabled=!busy&&session.raw_hold_files.isEmpty()) { Text("Propose holds from wall frame…") }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick=vm::undo,enabled=!busy) { Text("Undo edit") }
        OutlinedButton(onClick=vm::save,enabled=!busy) { Text("Save") }
        OutlinedButton(onClick=vm::library,enabled=!busy) { Text("Sessions") }
        OutlinedButton(onClick={deleteDialog=true},enabled=!busy) { Text("Delete session") }
    }
    if(uploadDialog) AlertDialog(onDismissRequest={uploadDialog=false},title={Text("Upload one wall frame?")},
        text={ Column { Text("The first analyzed frame, which may include people, will be sent to your local server and fal for SAM segmentation. Your full clip stays on this phone. Continue only with permission for everyone pictured. Provider retention is governed by fal's terms.")
            Text("Choose your development connection:")
            listOf("http://127.0.0.1:8000" to "USB: adb reverse", "http://10.0.2.2:8000" to "Android emulator").forEach { (url,label) ->
                TextButton(onClick={server=url}) { Text((if(server==url) "✓ " else "")+label) }
            }
        } },confirmButton={TextButton(onClick={uploadDialog=false;vm.automaticHolds(server)}) {Text("Upload and analyze")}},
        dismissButton={TextButton(onClick={uploadDialog=false}) {Text("Keep local")}})
    if(deleteDialog) AlertDialog(onDismissRequest={deleteDialog=false},title={Text("Delete this session?")},
        text={Text("Deletes the local clip, observations and corrections, plus the local backend upload if connected. Provider retention is separate.")},
        confirmButton={TextButton(onClick={deleteDialog=false;vm.deleteSession()}) {Text("Delete")}},
        dismissButton={TextButton(onClick={deleteDialog=false}) {Text("Cancel")}})
}
