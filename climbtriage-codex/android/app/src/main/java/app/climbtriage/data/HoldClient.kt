package app.climbtriage.data

import app.climbtriage.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** No provider credential belongs in this app. Only explicit keyframe uploads. */
class HoldClient(private val base:String) {
    private val client=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
    var jobId:String?=null
    private val jsonType="application/json".toMediaType()
    init { require(base in listOf("http://127.0.0.1:8000","http://10.0.2.2:8000")) }
    private fun send(path:String,method:String="GET",body:ByteArray?=null,headers:Map<String,String> = emptyMap(), binary:Boolean=false): JSONObject {
        val builder=Request.Builder().url(base+path)
        headers.forEach { (key,value)->builder.header(key,value) }
        builder.method(method,body?.toRequestBody(if(binary) "application/octet-stream".toMediaType() else jsonType))
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "Analysis server HTTP ${response.code}; check the local server and retry" }
            val input=response.body ?: error("Empty server response")
            require(input.contentLength() <= 4*1024*1024) { "Server response exceeds mobile limit" }
            val buffer=java.io.ByteArrayOutputStream()
            input.byteStream().use { stream ->
                val chunk=ByteArray(8192)
                while(true) { val count=stream.read(chunk); if(count<0) break
                    require(buffer.size()+count<=4*1024*1024) { "Server response exceeds mobile limit" }
                    buffer.write(chunk,0,count)
                }
            }
            return JSONObject(buffer.toString("UTF-8"))
        }
    }
    suspend fun analyze(image:File, stateFile:File, timeUs:Long, progress:(String)->Unit): JSONObject {
        val data=image.readBytes()
        val sha=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        var state=if(stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        if(state.optString("sha")!=sha || state.optString("base")!=base) state=JSONObject()
        fun persist() { stateFile.writeText(state.toString()) }
        if(!state.has("upload_id")) {
            val upload=send("/v1/uploads","POST",JSONObject().put("sha256",sha).put("size_bytes",data.size)
                .put("content_type","image/jpeg").toString().toByteArray())
            state.put("upload_id",upload.getString("id")).put("sha",sha).put("base",base).put("idempotency_key",newId())
            persist()
        }
        val uploadId=state.getString("upload_id")
        var offset=send("/v1/uploads/$uploadId").getInt("offset")
        while(offset<data.size) {
            currentCoroutineContext().ensureActive()
            val end=minOf(offset+512*1024,data.size)
            offset=send("/v1/uploads/$uploadId","PATCH",data.copyOfRange(offset,end),mapOf("Upload-Offset" to offset.toString()),true).getInt("offset")
            progress("Uploading wall frame: $offset / ${data.size} bytes")
        }
        if(!state.has("job_id")) {
            val job=send("/v1/jobs","POST",JSONObject().put("upload_id",uploadId).put("timestamp_us",timeUs)
                .put("coordinate_space",SPACE).put("consent_to_provider",true).toString().toByteArray(),
                mapOf("Idempotency-Key" to state.getString("idempotency_key")))
            state.put("job_id",job.getString("id")); persist()
        }
        jobId=state.getString("job_id")
        while(true) {
            currentCoroutineContext().ensureActive()
            val job=send("/v1/jobs/$jobId")
            progress("Hold analysis: ${job.getString("phase")} (${job.getInt("completed_units")}/3 stages)")
            when(job.getString("status")) {
                "succeeded" -> return job.getJSONObject("result")
                "failed", "cancelled", "deleted" -> {
                    state.remove("job_id"); state.put("idempotency_key",newId()); persist()
                    error(job.optString("error","Hold analysis stopped"))
                }
            }
            delay(1500)
        }
    }
    fun cancel() { jobId?.let { runCatching { send("/v1/jobs/$it/cancel","POST",ByteArray(0)) } } }
    fun deleteUpload(stateFile:File) {
        if(!stateFile.exists()) return
        val state=JSONObject(stateFile.readText())
        val request=Request.Builder().url(base+"/v1/uploads/"+state.getString("upload_id")).delete().build()
        client.newCall(request).execute().use { require(it.isSuccessful) { "Could not delete uploaded wall frame" } }
        stateFile.delete()
    }
    companion object {
        fun holds(result:JSONObject):List<Hold> {
            require(result.getInt("schema_version")==1)
            val array=result.getJSONArray("holds")
            return (0 until array.length()).map { i ->
                val h=array.getJSONObject(i); val parts=h.getJSONArray("parts")
                val rings=(0 until parts.length()).map { j ->
                    val ring=parts.getJSONArray(j)
                    (0 until ring.length()).map { k -> val p=ring.getJSONArray(k); Point(p.getDouble(0).toFloat(),p.getDouble(1).toFloat()) }
                }
                require(rings.isNotEmpty() && rings.all { Geometry.valid(it) }) { "Invalid hold polygon" }
                val p=h.getJSONObject("provenance")
                Hold(id=h.getString("id"),display_number=h.getInt("display_number"),parts=rings,
                    timestamp_us=h.getLong("timestamp_us"),validity="candidate",confidence=if(h.isNull("confidence")) null else h.getDouble("confidence").toFloat(),
                    provenance=Provenance(p.getString("provider"),p.getString("model"),p.getString("model_revision"),p.getString("algorithm"),p.getString("config_hash")))
            }
        }
    }
}
