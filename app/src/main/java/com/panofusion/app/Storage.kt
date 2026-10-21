package com.panofusion.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
@Entity(tableName = "projects")
data class Project(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String,
    val mode: String, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val revision: Int = 0, val coverage: Double? = null, val status: String = "待处理", val error: String? = null)
@Serializable
@Entity(tableName = "sessions", foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class Session(@PrimaryKey val id: String = UUID.randomUUID().toString(), val projectId: String,
    val createdAt: Long = System.currentTimeMillis(), val enabled: Boolean = true)
@Serializable
@Entity(tableName = "assets", foreignKeys = [ForeignKey(entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId"), Index("projectId")])
data class Asset(@PrimaryKey val id: String = UUID.randomUUID().toString(), val projectId: String,
    val sessionId: String, val sourceUri: String, val path: String, val mime: String,
    val enabled: Boolean = true, val analyzed: Boolean = false, val displayName: String = "素材", val width: Int = 0, val height: Int = 0, val durationMs: Long? = null, val rotation: Int = 0)
@Serializable
@Entity(tableName = "frames", indices = [Index("projectId"), Index("assetId")])
data class Frame(@PrimaryKey val id: String = UUID.randomUUID().toString(), val projectId: String,
    val assetId: String, val path: String, val timeUs: Long, val sharpness: Double, val features: Int,
    val selected: Boolean, val reason: String, val enabled: Boolean = true)
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun observe(): Flow<List<Project>>
    @Query("SELECT * FROM projects WHERE id=:id") suspend fun project(id: String): Project?
    @Upsert suspend fun save(project: Project)
    @Insert suspend fun addSession(session: Session)
    @Insert suspend fun addAsset(asset: Asset)
    @Transaction suspend fun restore(project: Project, sessions: List<Session>, assets: List<Asset>) { save(project); sessions.forEach { addSession(it) }; assets.forEach { addAsset(it) } }
    @Transaction suspend fun importSession(session: Session, assets: List<Asset>) { addSession(session); assets.forEach { addAsset(it) } }
    @Query("UPDATE assets SET width=:width, height=:height, durationMs=:duration, rotation=:rotation WHERE id=:id") suspend fun metadata(id: String, width: Int, height: Int, duration: Long?, rotation: Int)
    @Query("UPDATE assets SET analyzed=0 WHERE projectId=:id") suspend fun resetAnalysis(id: String)
    @Query("UPDATE assets SET analyzed=1 WHERE id=:id") suspend fun analyzed(id: String)
    @Query("DELETE FROM assets WHERE sessionId=:id") suspend fun deleteSessionAssets(id: String)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM frames WHERE assetId=:id") suspend fun clearAssetFrames(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFrame(frame: Frame)
    @Query("SELECT * FROM sessions WHERE projectId=:id ORDER BY createdAt") fun sessions(id: String): Flow<List<Session>>
    @Query("SELECT * FROM sessions WHERE projectId=:id ORDER BY createdAt") suspend fun allSessions(id: String): List<Session>
    @Query("SELECT * FROM assets WHERE projectId=:id") suspend fun assets(id: String): List<Asset>
    @Query("SELECT * FROM frames WHERE projectId=:id ORDER BY rowid") suspend fun frames(id: String): List<Frame>
    @Query("UPDATE sessions SET enabled=:enabled WHERE id=:id") suspend fun enableSession(id: String, enabled: Boolean)
    @Query("UPDATE frames SET enabled=:enabled WHERE id=:id") suspend fun enableFrame(id: String, enabled: Boolean)
    @Query("DELETE FROM frames WHERE projectId=:id") suspend fun clearFrames(id: String)
    @Query("DELETE FROM projects WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities = [Project::class, Session::class, Asset::class, Frame::class], version = 1, exportSchema = false)
abstract class ProjectDatabase: RoomDatabase() { abstract fun dao(): ProjectDao }
@Serializable
data class ProjectManifest(val formatVersion: Int = 1, val project: Project, val sessions: List<Session>, val assets: List<Asset>, val frames: List<Frame> = emptyList())
class ProjectStore(val context: Context, val dao: ProjectDao) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun directory(id: String): File { require(runCatching { UUID.fromString(id) }.isSuccess); return File(context.filesDir, "projects/$id").apply { mkdirs() } }
    suspend fun create(name: String, mode: String): Project = Project(name = name.ifBlank { "未命名全景" }, mode = mode).also { dao.save(it); snapshot(it.id) }
    suspend fun snapshot(id: String) {
        val p = dao.project(id) ?: return
        val output = File(directory(id), "project.json")
        val temp = File(output.parentFile, "project.json.tmp")
        temp.writeText(json.encodeToString(ProjectManifest(project = p, sessions = dao.allSessions(id), assets = dao.assets(id), frames = dao.frames(id))))
        check(temp.renameTo(output)) { "工程清单保存失败" }
    }
    suspend fun import(id: String, uris: List<Uri>) {
        require(uris.isNotEmpty())
        val session = Session(projectId = id)
        val copied = mutableListOf<Asset>()
        try {
            for (uri in uris) {
                val mime = context.contentResolver.getType(uri) ?: if (uri.scheme == "file" && File(requireNotNull(uri.path)).canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) {
                    when (File(uri.path!!).extension) { "jpg" -> "image/jpeg"; "mp4" -> "video/mp4"; else -> error("无法识别素材类型") }
                } else error("无法识别素材类型")
                require(mime.startsWith("image/") || mime.startsWith("video/")) { "不支持的素材类型：$mime" }
                val displayName = if (uri.scheme == "file") File(uri.path!!).name else runCatching {
                    context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }.getOrNull() ?: "导入素材"
                val assetId = UUID.randomUUID().toString()
                val dir = File(directory(id), "original/${session.id}").apply { mkdirs() }
                val file = File(dir, assetId)
                require(dir.usableSpace > 128L * 1024 * 1024) { "存储空间不足" }
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "素材访问权限已失效，请重新选择" }
                    file.outputStream().use { input.copyTo(it) }
                }
                require(file.length() > 0) { "素材文件为空" }
                copied += Asset(id = assetId, projectId = id, sessionId = session.id, sourceUri = uri.toString(), path = file.absolutePath, mime = mime, displayName = displayName)
            }
            dao.importSession(session, copied)
            dao.project(id)?.let { dao.save(it.copy(updatedAt = System.currentTimeMillis(), status = "素材已导入")) }
            snapshot(id)
        } catch (e: Exception) {
            // Only the private copy is removed; the external URI is never deleted.
            File(directory(id), "original/${session.id}").deleteRecursively()
            throw e
        }
    }
    suspend fun restoreRevision(id: String, revision: Int) {
        val project = requireNotNull(dao.project(id))
        val root = directory(id)
        val result = File(root,"history/revision-$revision")
        val manifest = File(result,"inputs.json")
        require(manifest.isFile && File(result,"panorama.png").isFile) { "此版本缺少可恢复输入" }
        val oldFrames = json.decodeFromString<List<Frame>>(manifest.readText())
        fun key(path: String) = path.replace('\\','/').substringAfter("/frames/")
        val keys = oldFrames.map { key(it.path) }.toSet()
        val frames = dao.frames(id)
        val usedAssets = frames.filter { key(it.path) in keys }.map { it.assetId }.toSet()
        require(usedAssets.isNotEmpty()) { "版本素材已删除，无法恢复" }
        val sessionIds = dao.assets(id).filter { it.id in usedAssets }.map { it.sessionId }.toSet()
        dao.allSessions(id).forEach { dao.enableSession(it.id,it.id in sessionIds) }
        frames.forEach { dao.enableFrame(it.id,key(it.path) in keys) }
        val bitmap = android.graphics.BitmapFactory.decodeFile(File(result,"coverage.png").path) ?: error("覆盖 Mask 无法读取")
        val coverage = try {
            val pixels = IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            Coverage.percentage(ByteArray(pixels.size) { if (android.graphics.Color.red(pixels[it])>0) -1 else 0 },bitmap.width,bitmap.height,project.mode=="180")
        } finally { bitmap.recycle() }
        dao.save(project.copy(revision = revision,coverage = if(project.mode=="自定义") null else coverage,status = "已恢复版本 $revision",error = null,updatedAt = System.currentTimeMillis()))
        snapshot(id)
    }
    suspend fun rename(id: String, name: String) { val project = requireNotNull(dao.project(id)); dao.save(project.copy(name = name.ifBlank { project.name }, updatedAt = System.currentTimeMillis())); snapshot(id) }
    suspend fun duplicate(id: String): String {
        val project = requireNotNull(dao.project(id))
        require(project.status != "处理中") { "请等待当前处理完成后复制工程" }
        val newId = UUID.randomUUID().toString()
        val source = directory(id)
        val target = directory(newId)
        try {
            check(source.copyRecursively(target, overwrite = true))
            val sessions = dao.allSessions(id)
            val sessionIds = sessions.associate { it.id to UUID.randomUUID().toString() }
            val assets = dao.assets(id)
            val assetIds = assets.associate { it.id to UUID.randomUUID().toString() }
            fun relocated(path: String) = File(target, File(path).relativeTo(source).path).absolutePath
            dao.restore(project.copy(id = newId, name = project.name + "（副本）"), sessions.map { it.copy(id = sessionIds.getValue(it.id), projectId = newId) },
                assets.map { it.copy(id = assetIds.getValue(it.id), projectId = newId, sessionId = sessionIds.getValue(it.sessionId), path = relocated(it.path)) })
            dao.frames(id).forEach { dao.addFrame(it.copy(id = UUID.randomUUID().toString(), projectId = newId, assetId = assetIds.getValue(it.assetId), path = relocated(it.path))) }
            snapshot(newId)
            return newId
        } catch (e: Exception) { dao.clearFrames(newId); dao.delete(newId); target.deleteRecursively(); throw e }
    }
    suspend fun delete(id: String) { dao.clearFrames(id); dao.delete(id); directory(id).deleteRecursively() }
}
@dagger.hilt.android.HiltAndroidApp
class PanoApplication: Application() {
    @javax.inject.Inject lateinit var database: ProjectDatabase
    @javax.inject.Inject lateinit var store: ProjectStore
}
