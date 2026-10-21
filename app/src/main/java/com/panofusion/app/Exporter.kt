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
    enum class Content { PANORAMA, COVERAGE_MASK }
    data class Options(val jpeg: Boolean, val width: Int, val quality: Int = 95, val background: Int = Color.rgb(23,26,31),
        val content: Content = Content.PANORAMA, val cropToCoverage: Boolean = false)
    /** Legacy entry point: full-resolution panorama, dark grey missing area for JPEG. */
    fun image(context: Context, source: File, uri: Uri, jpeg: Boolean) {
        val width = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(source.path, it) }.outWidth
        export(context, source.parentFile!!, uri, Options(jpeg, width), mode = "360", coverage = null)
    }
    /**
     * Exports a committed revision. Output never exceeds the rendered resolution: enlarging would
     * only interpolate the existing pixels, so larger sizes require a higher native render instead.
     */
    fun export(context: Context, revision: File, uri: Uri, options: Options, mode: String, coverage: Double?) {
        val mask = options.content == Content.COVERAGE_MASK
        require(!(mask && options.jpeg)) { "Coverage Mask 只导出为 PNG" }
        require(options.quality in 50..100) { "JPEG 质量范围 50–100" }
        val source = File(revision, if (mask) "coverage.png" else "panorama.png")
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("结果图片无法读取")
        val created = mutableListOf<Bitmap>()
        try {
            val fullWidth = bitmap.width; val fullHeight = bitmap.height
            require(options.width in 256..fullWidth && options.width % 2 == 0) { "导出宽度须为 256–$fullWidth 的偶数。当前结果按 ${fullWidth}×$fullHeight 渲染，更大尺寸需先在设置中提高原生渲染宽度。" }
            val bounds = if (options.cropToCoverage) validBounds(File(revision, "coverage.png")) ?: error("没有有效覆盖区域可裁剪") else Rect(0, 0, fullWidth, fullHeight)
            val cropped = if (bounds.width() == fullWidth && bounds.height() == fullHeight) bitmap else Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height()).also { created += it }
            val factor = options.width.toDouble() / fullWidth
            val outW = maxOf(1, Math.round(bounds.width() * factor).toInt()); val outH = maxOf(1, Math.round(bounds.height() * factor).toInt())
            val scaled = if (outW == cropped.width && outH == cropped.height) cropped else Bitmap.createScaledBitmap(cropped, outW, outH, !mask).also { created += it }
            val result = if (options.jpeg) Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
                created += it; val canvas = Canvas(it); canvas.drawColor(options.background); canvas.drawBitmap(scaled, 0f, 0f, null)
            } else scaled
            val bytes = java.io.ByteArrayOutputStream().also { check(result.compress(if (options.jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, options.quality, it)) }.toByteArray()
            val fullOutW = Math.round(fullWidth * factor).toInt(); val fullOutH = Math.round(fullHeight * factor).toInt()
            val xmp = if (mask) null else PanoMetadata.xmp(PanoMetadata.Layout(fullOutW, fullOutH, Math.round(bounds.left * factor).toInt(), Math.round(bounds.top * factor).toInt(), outW, outH),
                mode, coverage, if (options.jpeg) "#%06X".format(options.background and 0xFFFFFF) else "transparent")
            val output = when { xmp == null -> bytes; options.jpeg -> PanoMetadata.insertJpegXmp(bytes, xmp); else -> PanoMetadata.insertPngXmp(bytes, xmp) }
            context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out) { "无法写入所选位置" }; out.write(output) }
        } finally { created.forEach { it.recycle() }; bitmap.recycle() }
    }
    /** Bounding box of real coverage; a region spanning the 180° seam keeps the full width. */
    fun validBounds(mask: File): Rect? {
        val bitmap = BitmapFactory.decodeFile(mask.path) ?: return null
        try {
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) if (Color.red(row[x]) > 0) { if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; bottom = y }
            }
            return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
        } finally { bitmap.recycle() }
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
