package com.panofusion.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.File

class PanoramaWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "全景处理", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "processing")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("PanoFusion 360")
            .setContentText("正在本机处理全景素材").setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(1001, notification, if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result {
        val projectId = inputData.getString("project") ?: return Result.failure()
        val app = applicationContext as PanoApplication
        val dao = app.database.dao()
        val store = app.store
        val settings = SettingsStore.flow(applicationContext).first()
        val initial = dao.project(projectId) ?: return Result.failure()
        val staging = File(store.directory(projectId), "cache/job-$id")
        val metrics = JobMetrics()
        try {
            setForeground(getForegroundInfo())
            dao.save(initial.copy(status = "处理中", error = null))
            val sessions = dao.allSessions(projectId).filter { it.enabled }.map { it.id }.toSet()
            val assets = dao.assets(projectId).filter { it.enabled && it.sessionId in sessions }
            if (assets.isEmpty()) {
                dao.save(initial.copy(revision = 0, coverage = null, status = "无启用素材", error = null))
                store.snapshot(projectId)
                return Result.success()
            }
            val projections = assets.filter { it.mime.startsWith("video/") }.associate { it.id to VideoProjection.detect(File(it.path)) }
            require(projections.values.none { it == VideoProjection.Type.STEREO || it == VideoProjection.Type.OTHER_PROJECTION }) { "E_PROJECTION：双目或其他投影视频尚不支持，不能作为普通视频拼接" }
            val erp = projections.values.any { it == VideoProjection.Type.MONO_ERP }
            require(!erp || assets.size == 1) { "ERP 视频只支持单素材最佳帧导出，请单独创建工程" }
            var previous: String? = null
            for ((index, asset) in assets.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress("分析素材", index, assets.size)
                val existing = dao.frames(projectId).filter { it.assetId == asset.id }
                if (asset.analyzed && existing.isNotEmpty()) { previous = existing.lastOrNull { it.selected && it.enabled }?.path ?: previous; continue }
                dao.clearAssetFrames(asset.id)
                val frameDir = File(store.directory(projectId), "frames/${asset.id}")
                suspend fun accept(file: File, us: Long) {
                    currentCoroutineContext().ensureActive()
                    val values = NativePanorama.analyze(file.absolutePath, previous)
                    val reason = when {
                        values[0] < settings.sharpness -> "画面模糊"
                        !erp && values[1] < 60 -> "特征点不足"
                        values[2] > 0.75 -> "严重过曝或欠曝"
                        !erp && previous != null && values[3] > 0.03 && values[4] < 14 -> "与上一帧重复"
                        else -> "已选中"
                    }
                    val selected = reason == "已选中"
                    dao.addFrame(Frame(projectId = projectId, assetId = asset.id, path = file.absolutePath, timeUs = us,
                        sharpness = values[0], features = values[1].toInt(), selected = selected, reason = reason))
                    if (selected) previous = file.absolutePath
                }
                val analysisStarted = android.os.SystemClock.elapsedRealtime()
                if (asset.mime.startsWith("video/")) {
                    val metadata = VideoFrameExtractor().extract(asset.path, frameDir, settings.samplingUs) { file, us, fraction ->
                        progress("视频解码 ${index + 1}/${assets.size}", (fraction * 1000).toInt(), 1000)
                        accept(file, us)
                    }
                    dao.metadata(asset.id,metadata.width,metadata.height,metadata.durationUs/1000,metadata.rotation)
                } else {
                    val file = File(frameDir, "photo.jpg")
                    ImageInput.prepare(asset.path, file)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(asset.path,bounds)
                    val rotation = androidx.exifinterface.media.ExifInterface(asset.path).rotationDegrees
                    dao.metadata(asset.id,bounds.outWidth,bounds.outHeight,null,rotation)
                    accept(file, 0)
                }
                dao.analyzed(asset.id)
                metrics.analysisMs += android.os.SystemClock.elapsedRealtime() - analysisStarted
            }
            val activeIds = assets.map { it.id }.toSet()
            val allFrames = dao.frames(projectId)
            require(assets.all { asset -> allFrames.any { it.assetId == asset.id && it.selected } }) { "E_ASSET_QUALITY：至少一项素材没有可用关键帧，请检查淘汰原因或禁用该批次" }
            var frames = allFrames.filter { it.assetId in activeIds && it.selected && it.enabled }
            if (!erp && frames.size > KeyframeBudget.LIMIT) {
                // Long, steady captures yield more distinct frames than the engine budget. Keep an
                // even spread in time per asset and record why the others were left out.
                val kept = KeyframeBudget.thin(frames, KeyframeBudget.LIMIT) { it.assetId }.map { it.id }.toSet()
                frames.filter { it.id !in kept }.forEach { dao.addFrame(it.copy(selected = false, reason = "自动精简（超过 ${KeyframeBudget.LIMIT} 张上限）")) }
                frames = frames.filter { it.id in kept }
            }
            require(frames.size >= if (erp) 1 else 2) { "E_FRAMES：清晰且非重复的关键帧不足。请补充有纹理且互相重叠的画面。" }
            staging.mkdirs()
            val anchorKey = initial.anchorFrame
            val anchorIndex = frames.indexOfFirst { frameKey(it.path) == anchorKey }
            val anchorMoved = !erp && anchorKey != null && anchorIndex < 0
            val stitchStarted = android.os.SystemClock.elapsedRealtime()
            val coverage = if (erp) {
                require(initial.mode == "360") { "完整单目 ERP 视频请使用 360° 工程" }
                val best = frames.maxBy { it.sharpness }
                val bitmap = android.graphics.BitmapFactory.decodeFile(best.path) ?: error("ERP 帧读取失败")
                try {
                    require(bitmap.width == bitmap.height * 2) { "已标记 ERP 视频的画布比例不受支持" }
                    val oriented = rotateErpYaw(bitmap, initial.referenceYaw)
                    try { File(staging,"panorama.png").outputStream().use { oriented.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                    finally { if (oriented !== bitmap) oriented.recycle() }
                    val mask = android.graphics.Bitmap.createBitmap(bitmap.width,bitmap.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try { mask.eraseColor(android.graphics.Color.WHITE); File(staging,"coverage.png").outputStream().use { mask.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { mask.recycle() }
                } finally { bitmap.recycle() }
                100.0
            } else NativePanorama.stitch(frames.map { it.path }.toTypedArray(), staging.absolutePath, settings.outputWidth, initial.mode, anchorIndex.coerceAtLeast(0), initial.referenceYaw, object: EngineCallback {
                override fun stage(name: String, completed: Int, total: Int) { metrics.stage(name); setProgressAsync(workDataOf("stage" to name, "done" to completed, "total" to total)) }
                override fun cancelled() = isStopped
            })
            metrics.stage(null)
            metrics.stitchMs = android.os.SystemClock.elapsedRealtime() - stitchStarted
            currentCoroutineContext().ensureActive()
            metrics.write(File(staging, "metrics.json"), frames.size, File(staging, "panorama.png").length())
            // Publish only complete revisions. A failed or cancelled rebuild retains the last good result.
            val history = File(store.directory(projectId), "history")
            val revision = maxOf(initial.revision, history.listFiles()?.mapNotNull { it.name.removePrefix("revision-").toIntOrNull() }?.maxOrNull() ?: 0) + 1
            File(staging,"inputs.json").writeText(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Frame.serializer()), frames))
            val destination = File(store.directory(projectId), "history/revision-$revision")
            destination.parentFile?.mkdirs()
            check(!destination.exists() && staging.renameTo(destination)) { "E_SAVE：无法提交新版本" }
            val committed = (dao.project(projectId) ?: error("工程已删除")).copy(revision = revision, coverage = coverage.takeIf { it >= 0 }, updatedAt = System.currentTimeMillis(), status = "处理完成",
                anchorFrame = if (erp) null else frameKey(frames[anchorIndex.coerceAtLeast(0)].path),
                error = if (anchorMoved) "原参考锚点帧已禁用，已改用当前第一张关键帧作为参考方向" else null)
            dao.save(committed)
            store.snapshot(projectId)
            return Result.success()
        } catch (e: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                dao.project(projectId)?.let { dao.save(it.copy(status = "已取消", error = "结果保留，可重新处理")) }
            }
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            // The native engine reports cancellation as E_CANCELLED at its next stage boundary.
            val cancelled = isStopped || e.message.orEmpty().startsWith("E_CANCELLED")
            val message = if (e is OutOfMemoryError) "E_MEMORY：内存不足，请减少素材、禁用部分关键帧或降低输出宽度" else e.message ?: "E_NATIVE：原生处理失败"
            dao.project(projectId)?.let { dao.save(if (cancelled) it.copy(status = "已取消", error = "结果保留，可重新处理") else it.copy(status = "处理失败", error = message)) }
            return Result.failure(workDataOf("error" to (e.message ?: "处理失败")))
        } finally { staging.deleteRecursively() }
    }
    /** Real wall-clock timings and the process peak RSS (VmHWM, reset at job start when the kernel allows it). */
    private class JobMetrics {
        private val resetPeak = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        private val started = android.os.SystemClock.elapsedRealtime()
        var analysisMs = 0L
        var stitchMs = 0L
        private val stages = linkedMapOf<String, Long>()
        private var current: String? = null
        private var since = 0L
        fun stage(name: String?) {
            val now = android.os.SystemClock.elapsedRealtime()
            current?.let { stages[it] = (stages[it] ?: 0) + now - since }
            if (name != current) { current = name; since = now } else since = now
            if (name == null) current = null
        }
        fun write(file: File, frames: Int, outputBytes: Long) {
            val peak = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong() * 1024 }.getOrDefault(-1)
            file.writeText(kotlinx.serialization.json.buildJsonObject {
                put("frames", kotlinx.serialization.json.JsonPrimitive(frames))
                put("analysisMs", kotlinx.serialization.json.JsonPrimitive(analysisMs))
                put("stitchMs", kotlinx.serialization.json.JsonPrimitive(stitchMs))
                put("totalMs", kotlinx.serialization.json.JsonPrimitive(android.os.SystemClock.elapsedRealtime() - started))
                put("peakRssBytes", kotlinx.serialization.json.JsonPrimitive(peak))
                put("peakRssResetAtJobStart", kotlinx.serialization.json.JsonPrimitive(resetPeak))
                put("nativeHeapBytes", kotlinx.serialization.json.JsonPrimitive(android.os.Debug.getNativeHeapAllocatedSize()))
                put("panoramaBytes", kotlinx.serialization.json.JsonPrimitive(outputBytes))
                put("stagesMs", kotlinx.serialization.json.JsonObject(stages.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            }.toString())
        }
    }
    private suspend fun progress(stage: String, done: Int, total: Int) = setProgress(workDataOf("stage" to stage, "done" to done, "total" to total))
    companion object {
        /** Moves the content at [yaw] degrees to the ERP centre; an exact column rotation, no resampling. */
        fun rotateErpYaw(source: android.graphics.Bitmap, yaw: Double): android.graphics.Bitmap {
            val w = source.width
            val dx = Math.floorMod(Math.round(-yaw / 360.0 * w).toInt(), w)
            if (dx == 0) return source
            val out = android.graphics.Bitmap.createBitmap(w, source.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawBitmap(source, dx.toFloat(), 0f, null); drawBitmap(source, (dx - w).toFloat(), 0f, null) }
            return out
        }
        fun enqueue(context: Context, projectId: String) {
            val request = OneTimeWorkRequestBuilder<PanoramaWorker>().setInputData(workDataOf("project" to projectId)).addTag(projectId).build()
            WorkManager.getInstance(context).enqueueUniqueWork("panorama-$projectId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Keeps at most [limit] frames: every asset keeps at least one, the rest is shared by frame count and spread evenly in time. */
object KeyframeBudget {
    const val LIMIT = 24
    fun <T> thin(frames: List<T>, limit: Int, group: (T) -> String): List<T> {
        if (frames.size <= limit) return frames
        val groups = frames.groupBy(group)
        require(groups.size <= limit) { "E_FRAMES：素材数量超过单次融合上限 $limit，请停用部分批次" }
        val quota = groups.mapValues { (_, list) -> maxOf(1, list.size * limit / frames.size) }.toMutableMap()
        while (quota.values.sum() > limit) { val k = quota.filterValues { it > 1 }.maxBy { it.value }.key; quota[k] = quota.getValue(k) - 1 }
        while (quota.values.sum() < limit) { val k = groups.keys.filter { quota.getValue(it) < groups.getValue(it).size }.maxBy { groups.getValue(it).size.toDouble() / quota.getValue(it) }; quota[k] = quota.getValue(k) + 1 }
        val kept = groups.flatMap { (key, list) -> evenly(list, quota.getValue(key)) }.toSet()
        return frames.filter { it in kept }
    }
    /** [count] items evenly spaced over [items], always including the first and last. */
    fun <T> evenly(items: List<T>, count: Int): List<T> = when {
        count >= items.size -> items
        count == 1 -> listOf(items[items.size / 2])
        else -> (0 until count).map { items[Math.round(it * (items.size - 1).toDouble() / (count - 1)).toInt()] }
    }
}
