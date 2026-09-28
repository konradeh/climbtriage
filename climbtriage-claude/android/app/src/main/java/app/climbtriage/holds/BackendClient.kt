package app.climbtriage.holds

import app.climbtriage.contracts.AnalysisRun
import app.climbtriage.contracts.ContractJson
import app.climbtriage.contracts.CoordinateSystem
import app.climbtriage.contracts.Hold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Client for the ClimbTriage backend (docs/05). Holds no provider credentials: the phone uploads
 * one climber-free wall image — only after the user confirms — and receives hold candidates.
 * Uploads resume from the server's committed offset; job creation is idempotent per image.
 */
class BackendClient(private val baseUrl: String, private val token: String? = null) {

    @Serializable
    data class HoldResult(val schema: String, val run: AnalysisRun, val imageWidthPx: Int, val imageHeightPx: Int, val holds: List<Hold>)

    class BackendException(message: String) : IOException(message)

    data class Status(val status: String, val step: Int, val stepsTotal: Int, val stepName: String?)

    suspend fun health(): JsonObject = withContext(Dispatchers.IO) { request("GET", "/v1/health").second!! }

    suspend fun detectHolds(jpeg: ByteArray, onStatus: (Status) -> Unit): HoldResult = withContext(Dispatchers.IO) {
        val sha = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        val upload = request("POST", "/v1/uploads", buildJsonObject {
            put("sizeBytes", jpeg.size); put("sha256", sha); put("contentType", "image/jpeg")
        }).second!!
        val uploadId = upload["uploadId"]!!.jsonPrimitive.content
        uploadChunks(uploadId, jpeg)
        val job = request("POST", "/v1/jobs", buildJsonObject {
            put("kind", "hold_candidates"); put("uploadId", uploadId)
        }, headers = mapOf("Idempotency-Key" to "holds-$uploadId")).second!!
        val jobId = job["id"]!!.jsonPrimitive.content
        var wait = 500L
        var result: HoldResult? = null
        try {
            while (result == null) {
                val s = request("GET", "/v1/jobs/$jobId").second!!
                val status = Status(s["status"]!!.jsonPrimitive.content, s["step"]!!.jsonPrimitive.int,
                    s["stepsTotal"]!!.jsonPrimitive.int, s["stepName"]?.jsonPrimitive?.contentOrNull)
                onStatus(status)
                when (status.status) {
                    "succeeded" -> result = ContractJson.decodeFromJsonElement(HoldResult.serializer(), s["result"]!!)
                    "failed", "cancelled" -> throw BackendException(
                        "hold job ${status.status}: ${s["error"]?.jsonPrimitive?.contentOrNull ?: ""}")
                    else -> {
                        delay(wait)
                        wait = minOf(wait * 2, 4000L)
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { request("POST", "/v1/jobs/$jobId/cancel") }
            throw e
        }
        // The wall image is frame_norm of the reference capture; for a verified-still capture
        // wall_norm is that same grid (identity registration).
        val done = checkNotNull(result)
        done.copy(holds = done.holds.map { it.copy(coords = CoordinateSystem.WALL_NORM) })
    }

    private fun uploadChunks(uploadId: String, data: ByteArray, chunk: Int = 256 * 1024) {
        var attempts = 0
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunk, data.size) - 1
            try {
                val (code, body) = request("PUT", "/v1/uploads/$uploadId", raw = data.copyOfRange(offset, end + 1),
                    headers = mapOf("Content-Range" to "bytes $offset-$end/${data.size}"), allowConflict = true)
                offset = if (code == 409) {
                    // Server says where it is: resume from there (handles a lost response to a write that landed).
                    request("GET", "/v1/uploads/$uploadId").second!!["offset"]!!.jsonPrimitive.int
                } else body!!["offset"]!!.jsonPrimitive.int
                attempts = 0
            } catch (e: IOException) {
                if (++attempts > 3) throw e
                Thread.sleep(500L shl attempts)
                offset = request("GET", "/v1/uploads/$uploadId").second!!["offset"]!!.jsonPrimitive.int
            }
        }
    }

    private fun request(
        method: String, path: String, json: JsonObject? = null, raw: ByteArray? = null,
        headers: Map<String, String> = emptyMap(), allowConflict: Boolean = false,
    ): Pair<Int, JsonObject?> {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = json?.toString()?.toByteArray() ?: raw
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (json != null) "application/json" else "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 409 && allowConflict) return code to null
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw BackendException("HTTP $code on $method $path: ${text.take(300)}")
            return code to (if (text.isBlank()) null else ContractJson.parseToJsonElement(text).jsonObject)
        } finally {
            conn.disconnect()
        }
    }
}
