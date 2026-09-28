package app.climbtriage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.climbtriage.capture.ClipDecoder
import app.climbtriage.data.*
import app.climbtriage.domain.*
import app.climbtriage.perception.PoseEngine
import app.climbtriage.perception.RouteSuggester
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class Review(val session:Session,val raw:RawPose,val track:List<TrackedFrame>)
class ClimbViewModel(app:Application):AndroidViewModel(app) {
    val store=SessionStore(app)
    val library=store.library.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val mutableReview=MutableStateFlow<Review?>(null)
    val review=mutableReview.asStateFlow()
    val busy=MutableStateFlow(false)
    val suggestions=MutableStateFlow<Set<String>>(emptySet())
    val status=MutableStateFlow("Import a short SDR climbing clip. Media and pose stay on this phone.")
    private var task:Job?=null
    private var remote:HoldClient?=null
    private val undo=ArrayDeque<Session>()
    private fun publish(session:Session,raw:RawPose) {
        mutableReview.value=Review(session,raw,Tracking.select(raw.frames,session.seeds,raw.capture.width.toFloat()/raw.capture.height))
    }
    private fun work(block:suspend ()->Unit) {
        if(busy.value) return
        task=viewModelScope.launch {
            busy.value=true
            try { block() } catch(e:CancellationException) { status.value="Cancelled; saved sessions remain available"; throw e }
            catch(e:Exception) { status.value=e.message ?: e.javaClass.simpleName }
            finally { busy.value=false; remote=null }
        }
    }
    fun importClip(uri:Uri) = work {
        val id=newId()
        try {
            status.value="Copying original clip into private storage…"
            val sha=store.importClip(uri,id)
            val raw=withContext(Dispatchers.Default) {
                val frames=mutableListOf<PoseFrame>()
                PoseEngine(getApplication()).use { pose ->
                    val capture=ClipDecoder().decode(store.media(id),id,sha,{ bitmap,time,source ->
                        if(frames.isEmpty()) store.wallImage(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,94,it) }
                        frames+=pose.detect(bitmap,time,source)
                    },{ time,total -> status.value="On-device pose: ${time/1000} / ${total/1000} ms decoded (${frames.size} analyzed frames)" })
                    RawPose(capture=capture,provenance=pose.provenance,frames=frames)
                }
            }
            store.saveRaw(id,raw)
            val wall=WallVersion()
            val session=Session(id=id,name="Climb ${java.text.SimpleDateFormat("MMM d HH:mm",java.util.Locale.getDefault()).format(java.util.Date())}",
                wall=wall,route=RouteVersion(wall_version_id=wall.version_id))
            store.save(session); undo.clear(); suggestions.value=emptySet(); publish(session,raw)
            status.value="Saved locally. Pause on a visible climber, choose Person, then tap their body."
        } catch(e:Throwable) { withContext(NonCancellable) { store.delete(id) }; throw e }
    }
    fun open(item:SessionIndex)=work {
        val (session,raw)=store.load(item)
        undo.clear(); suggestions.value=emptySet(); publish(session,raw); status.value="Session reopened with original observations and corrections"
    }
    fun library() { if(!busy.value) mutableReview.value=null }
    fun save()=work { review.value?.let { store.save(it.session); status.value="Session saved" } }
    fun edit(transform:(Session)->Session)=work {
        val current=review.value ?: return@work
        val next=transform(current.session)
        store.save(next); undo.addLast(current.session)
        publish(next,current.raw); status.value="Saved correction"
    }
    fun undo()=work {
        val current=review.value ?: return@work
        if(undo.isEmpty()) { status.value="No edit to undo in this review"; return@work }
        val old=undo.removeLast()
        val wall=old.wall.copy(version_id=newId(),parent_version_id=current.session.wall.version_id)
        val route=old.route.copy(version_id=newId(),parent_version_id=current.session.route.version_id,wall_version_id=wall.version_id)
        val next=old.copy(wall=wall,route=route,corrections=current.session.corrections+Correction(timestamp_us=0,
            operation="undo",parent_version_id=current.session.wall.version_id,version_id=wall.version_id))
        store.save(next); publish(next,current.raw)
    }
    fun select(timeUs:Long,index:Int)=edit { s ->
        val seed=Seed(timeUs,index)
        s.copy(seeds=(s.seeds.filter { it.timestamp_us!=timeUs }+seed).sortedBy { it.timestamp_us },
            corrections=s.corrections+Correction(timestamp_us=timeUs,operation="person_seed",parent_version_id=s.wall.version_id,
                version_id=s.wall.version_id,affected_ids=listOf(seed.segment_id)))
    }
    fun suggestRoute(seedId:String)=work {
        val current=review.value ?: return@work
        suggestions.value=withContext(Dispatchers.Default) { RouteSuggester.suggest(store.wallImage(current.session.id),current.session.wall.holds,seedId) }
        status.value="Blue outlines are color/geometry suggestions. Same-color neighboring routes may be included; review before accepting."
    }
    fun acceptSuggestions()=edit { s ->
        val ids=suggestions.value.intersect(s.wall.holds.map { it.id }.toSet())
        suggestions.value=emptySet()
        val route=s.route.copy(version_id=newId(),parent_version_id=s.route.version_id,members=s.route.members+ids)
        s.copy(route=route,corrections=s.corrections+Correction(timestamp_us=0,operation="accept_color_geometry_v1",
            parent_version_id=s.route.version_id,version_id=route.version_id,affected_ids=ids.toList()))
    }
    fun automaticHolds(base:String)=work {
        val current=review.value ?: return@work
        require(current.session.raw_hold_files.isEmpty()) { "Automatic proposals already saved. Edit these candidates; reruns are a later correction workflow." }
        val client=HoldClient(base); remote=client
        val result=withContext(Dispatchers.IO) {
            client.analyze(store.wallImage(current.session.id),File(store.directory(current.session.id),"upload-state.json"),0) { status.value=it }
        }
        val holds=HoldClient.holds(result)
        val rawFile=withContext(Dispatchers.IO) { store.saveRawHolds(current.session.id,result.toString()) }
        // Preserve manual holds and immutable IDs; never overwrite user work with a cloud result.
        val existing=current.session.wall.holds
        val numbered=holds.mapIndexed { i,h -> h.copy(display_number=(existing.maxOfOrNull { it.display_number } ?: 0)+i+1) }
        val next=Editor.change(current.session,existing+numbered,"automatic_hold_proposals",numbered.map { it.id },0)
            .copy(raw_hold_files=current.session.raw_hold_files+rawFile)
        store.save(next); publish(next,current.raw)
        status.value="${holds.size} hold candidates saved. Select route members; missed holds can be added manually."
    }
    fun cancel() {
        val client=remote
        task?.cancel()
        viewModelScope.launch(Dispatchers.IO) { client?.cancel() }
    }
    fun deleteSession()=work {
        val current=review.value ?: return@work
        val state=File(store.directory(current.session.id),"upload-state.json")
        if(state.exists()) withContext(Dispatchers.IO) {
            val base=org.json.JSONObject(state.readText()).getString("base")
            HoldClient(base).deleteUpload(state)
        }
        store.delete(current.session.id); mutableReview.value=null; status.value="Session and local backend upload deleted"
    }
}
