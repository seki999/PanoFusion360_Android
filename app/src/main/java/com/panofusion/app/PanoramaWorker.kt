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
            }
            val activeIds = assets.map { it.id }.toSet()
            val allFrames = dao.frames(projectId)
            require(assets.all { asset -> allFrames.any { it.assetId == asset.id && it.selected } }) { "E_ASSET_QUALITY：至少一项素材没有可用关键帧，请检查淘汰原因或禁用该批次" }
            val frames = allFrames.filter { it.assetId in activeIds && it.selected && it.enabled }
            require(frames.size >= if (erp) 1 else 2) { "E_FRAMES：清晰且非重复的关键帧不足。请补充有纹理且互相重叠的画面。" }
            require(erp || frames.size <= 24) { "当前选中 ${frames.size} 张帧，首版最多融合 24 张，请在关键帧列表禁用重复帧" }
            staging.mkdirs()
            val coverage = if (erp) {
                require(initial.mode == "360") { "完整单目 ERP 视频请使用 360° 工程" }
                val best = frames.maxBy { it.sharpness }
                val bitmap = android.graphics.BitmapFactory.decodeFile(best.path) ?: error("ERP 帧读取失败")
                try {
                    require(bitmap.width == bitmap.height * 2) { "已标记 ERP 视频的画布比例不受支持" }
                    File(staging,"panorama.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    val mask = android.graphics.Bitmap.createBitmap(bitmap.width,bitmap.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try { mask.eraseColor(android.graphics.Color.WHITE); File(staging,"coverage.png").outputStream().use { mask.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { mask.recycle() }
                } finally { bitmap.recycle() }
                100.0
            } else NativePanorama.stitch(frames.map { it.path }.toTypedArray(), staging.absolutePath, settings.outputWidth, initial.mode, object: EngineCallback {
                override fun stage(name: String, completed: Int, total: Int) { setProgressAsync(workDataOf("stage" to name, "done" to completed, "total" to total)) }
                override fun cancelled() = isStopped
            })
            currentCoroutineContext().ensureActive()
            // Publish only complete revisions. A failed or cancelled rebuild retains the last good result.
            val history = File(store.directory(projectId), "history")
            val revision = maxOf(initial.revision, history.listFiles()?.mapNotNull { it.name.removePrefix("revision-").toIntOrNull() }?.maxOrNull() ?: 0) + 1
            File(staging,"inputs.json").writeText(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Frame.serializer()), frames))
            val destination = File(store.directory(projectId), "history/revision-$revision")
            destination.parentFile?.mkdirs()
            check(!destination.exists() && staging.renameTo(destination)) { "E_SAVE：无法提交新版本" }
            val committed = (dao.project(projectId) ?: error("工程已删除")).copy(revision = revision, coverage = coverage.takeIf { it >= 0 }, updatedAt = System.currentTimeMillis(), status = "处理完成", error = null)
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
            dao.project(projectId)?.let { dao.save(it.copy(status = "处理失败", error = e.message ?: "E_NATIVE：原生处理失败")) }
            return Result.failure(workDataOf("error" to (e.message ?: "处理失败")))
        } finally { staging.deleteRecursively() }
    }
    private suspend fun progress(stage: String, done: Int, total: Int) = setProgress(workDataOf("stage" to stage, "done" to done, "total" to total))
    companion object {
        fun enqueue(context: Context, projectId: String) {
            val request = OneTimeWorkRequestBuilder<PanoramaWorker>().setInputData(workDataOf("project" to projectId)).addTag(projectId).build()
            WorkManager.getInstance(context).enqueueUniqueWork("panorama-$projectId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
