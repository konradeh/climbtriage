package app.climbtriage.data

import android.content.Context
import android.net.Uri
import androidx.room.*
import app.climbtriage.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@Entity(tableName="sessions")
data class SessionIndex(@PrimaryKey val id: String, val name: String, val versionFile: String, val updated: Long)
@Dao interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updated DESC") fun observe(): Flow<List<SessionIndex>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(item: SessionIndex)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities=[SessionIndex::class],version=1,exportSchema=false)
abstract class SessionDb: RoomDatabase() { abstract fun sessions(): SessionDao }

class SessionStore(private val context: Context) {
    private val db=Room.databaseBuilder(context,SessionDb::class.java,"sessions.db").build()
    val library=db.sessions().observe()
    val json=Json { encodeDefaults=true; explicitNulls=true }
    fun directory(id: String)=File(context.filesDir,"sessions/$id").apply { mkdirs() }
    fun media(id: String)=File(directory(id),"original.mp4")
    fun wallImage(id: String)=File(directory(id),"wall.jpg")
    suspend fun importClip(uri: Uri, id: String): String = withContext(Dispatchers.IO) {
        val output=media(id)
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(output).use { out ->
                    val buffer=ByteArray(64*1024); var total=0L
                    while(true) {
                        val count=input.read(buffer); if(count<0) break
                        total+=count
                        require(total<=300L*1024*1024) { "First slice limits imports to 300 MiB" }
                        digest.update(buffer,0,count); out.write(buffer,0,count)
                    }
                    out.fd.sync()
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch(error: Throwable) { output.delete(); throw error }
    }
    suspend fun saveRaw(id: String, raw: RawPose) = withContext(Dispatchers.IO) {
        val file=File(directory(id),"raw-pose.json")
        check(!file.exists()) { "Raw outputs are immutable" }
        atomicWrite(file,json.encodeToString(raw))
    }
    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        val name="version-${newId()}.json"
        atomicWrite(File(directory(session.id),name),json.encodeToString(session))
        db.sessions().put(SessionIndex(session.id,session.name,name,System.currentTimeMillis()))
    }
    suspend fun load(item: SessionIndex): Pair<Session,RawPose> = withContext(Dispatchers.IO) {
        val session=json.decodeFromString<Session>(File(directory(item.id),item.versionFile).readText())
        val raw=json.decodeFromString<RawPose>(File(directory(item.id),"raw-pose.json").readText())
        require(session.schema_version==1 && raw.schema_version==1) { "Unsupported session version" }
        session to raw
    }
    fun saveRawHolds(id:String,text:String):String {
        val name="raw-holds-${newId()}.json"
        atomicWrite(File(directory(id),name),text)
        return name
    }
    private fun atomicWrite(file:File,text:String) {
        val pending=File(file.parentFile,file.name+".pending")
        FileOutputStream(pending).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(pending.renameTo(file)) { "Could not atomically commit session" }
    }
    suspend fun delete(id:String) = withContext(Dispatchers.IO) {
        db.sessions().delete(id)
        // UUID paths only; media never references arbitrary user paths.
        require(java.util.UUID.fromString(id).toString()==id)
        directory(id).deleteRecursively()
    }
}
