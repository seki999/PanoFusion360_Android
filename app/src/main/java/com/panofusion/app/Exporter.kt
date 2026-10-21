package com.panofusion.app

import android.content.Context
import android.graphics.*
import android.net.Uri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Exporter {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun image(context: Context, source: File, uri: Uri, jpeg: Boolean) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("结果图片无法读取")
        var flattened: Bitmap? = null
        try {
            val result = if (jpeg) Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                flattened = it; val canvas = Canvas(it); canvas.drawColor(Color.rgb(23,26,31)); canvas.drawBitmap(bitmap,0f,0f,null)
            } else bitmap
            context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out); check(result.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,95,out)) }
        } finally { flattened?.recycle(); bitmap.recycle() }
    }
    suspend fun archive(store: ProjectStore, id: String, uri: Uri) {
        store.snapshot(id)
        val root = store.directory(id)
        val project = store.dao.project(id) ?: error("工程不存在")
        val manifest = ProjectManifest(project = project, sessions = store.dao.allSessions(id), assets = store.dao.assets(id).map { it.copy(path = File(it.path).relativeTo(root).invariantSeparatorsPath) }, frames = store.dao.frames(id).map { it.copy(path = File(it.path).relativeTo(root).invariantSeparatorsPath) })
        store.context.contentResolver.openOutputStream(uri).use { stream ->
            requireNotNull(stream)
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("project.json")); zip.write(json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                root.walkTopDown().filter { it.isFile && it.name != "project.json" && !it.relativeTo(root).invariantSeparatorsPath.startsWith("cache/") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }
    suspend fun restore(store: ProjectStore, uri: Uri): String {
        val id = UUID.randomUUID().toString()
        val root = store.directory(id)
        try {
            var bytes = 0L
            var entries = 0
            store.context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 10000) { "工程包文件数过多" }
                        val file = File(root, entry.name).canonicalFile
                        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile) { "工程包包含不安全路径" }
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            require(!file.exists()) { "工程包包含重复文件" }
                            file.outputStream().use { out ->
                                val buffer = ByteArray(65536)
                                while (true) { val size = zip.read(buffer); if (size < 0) break; bytes += size; require(bytes <= 8L*1024*1024*1024 && root.usableSpace > 32L*1024*1024) { "工程包过大或存储空间不足" }; out.write(buffer,0,size) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val manifestFile = File(root,"project.json")
            require(manifestFile.length() in 1..4_000_000) { "缺少合法工程清单" }
            val manifest = json.decodeFromString<ProjectManifest>(manifestFile.readText())
            require(manifest.formatVersion == 1) { "不支持此工程版本" }
            val sessionMap = manifest.sessions.associate { it.id to UUID.randomUUID().toString() }
            val assetIds = manifest.assets.associate { it.id to UUID.randomUUID().toString() }
            val assets = manifest.assets.map { asset ->
                val file = File(root,asset.path).canonicalFile
                require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "工程素材丢失" }
                asset.copy(id = assetIds.getValue(asset.id), projectId = id, sessionId = sessionMap[asset.sessionId] ?: error("批次引用无效"), path = file.path, analyzed = manifest.frames.any { it.assetId == asset.id })
            }
            require(manifest.project.mode in listOf("360","180","自定义")) { "工程模式无效" }
            val project = manifest.project.copy(id = id, name = manifest.project.name + "（导入）", status = "工程已恢复", error = null)
            store.dao.restore(project, manifest.sessions.map { it.copy(id = sessionMap.getValue(it.id),projectId = id) }, assets)
            manifest.frames.forEach { frame ->
                val file = File(root,frame.path).canonicalFile
                require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "关键帧丢失或路径无效" }
                store.dao.addFrame(frame.copy(id = UUID.randomUUID().toString(), projectId = id, assetId = assetIds[frame.assetId] ?: error("关键帧引用无效"), path = file.path))
            }
            store.snapshot(id)
            return id
        } catch (e: Exception) { store.dao.clearFrames(id); store.dao.delete(id); root.deleteRecursively(); throw e }
    }
}
