package com.panofusion.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class Review(val sessionId: String, val revision: Int, val coverage: Double?)

@Composable
fun ExportDialog(source: File, project: Project, onDismiss: () -> Unit, onExport: (Exporter.Options) -> Unit) {
    val rendered = remember(source) { BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(source.path, it) }.outWidth }
    var jpeg by remember { mutableStateOf(true) }
    var mask by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(rendered.toString()) }
    var quality by remember { mutableStateOf(92f) }
    var background by remember { mutableStateOf(android.graphics.Color.rgb(23,26,31)) }
    var crop by remember { mutableStateOf(false) }
    val parsed = width.toIntOrNull()
    val valid = parsed != null && parsed in 256..rendered && parsed % 2 == 0
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导出") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { FilterChip(selected = !mask, onClick = { mask = false }, label = { Text("全景") }); Spacer(Modifier.width(6.dp)); FilterChip(selected = mask, onClick = { mask = true; jpeg = false }, label = { Text("Coverage Mask") }) }
        if (!mask) Row { FilterChip(selected = jpeg, onClick = { jpeg = true }, label = { Text("JPEG") }); Spacer(Modifier.width(6.dp)); FilterChip(selected = !jpeg, onClick = { jpeg = false }, label = { Text("PNG（透明缺失区）") }) }
        Row { listOf(2048, 4096, 8192).forEach { w -> FilterChip(enabled = w <= rendered, selected = parsed == w, onClick = { width = w.toString() }, label = { Text("$w") }); Spacer(Modifier.width(4.dp)) } }
        OutlinedTextField(value = width, onValueChange = { width = it.filter(Char::isDigit).take(5) }, label = { Text("画布宽度（高度为一半）") }, isError = !valid, singleLine = true)
        Text("本结果按 ${rendered}×${rendered / 2} 渲染；不提供放大导出。", style = MaterialTheme.typography.labelSmall)
        if (jpeg && !mask) {
            Text("JPEG 质量 ${quality.roundToInt()}")
            Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
            Text("缺失区域背景")
            Row { listOf("深灰" to android.graphics.Color.rgb(23,26,31), "黑" to android.graphics.Color.BLACK, "白" to android.graphics.Color.WHITE).forEach { (label, color) ->
                FilterChip(selected = background == color, onClick = { background = color }, label = { Text(label) }); Spacer(Modifier.width(4.dp)) } }
        }
        if (!mask) Row { Checkbox(checked = crop, onCheckedChange = { crop = it }); Text("裁剪到实际有效区域（写入 GPano 裁剪区域）", Modifier.padding(top = 12.dp)) }
        val complete = PanoMetadata.completeSphere(project.mode, project.coverage)
        Text(if (complete || crop) "将写入 Photo Sphere 元数据。" else "覆盖不完整：不写入完整球面元数据，仅记录实际覆盖率。", style = MaterialTheme.typography.labelSmall)
    } }, confirmButton = { TextButton(enabled = valid, onClick = {
        onExport(Exporter.Options(jpeg = jpeg && !mask, width = parsed!!, quality = quality.roundToInt(), background = background,
            content = if (mask) Exporter.Content.COVERAGE_MASK else Exporter.Content.PANORAMA, cropToCoverage = crop && !mask))
    }) { Text("选择保存位置") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ProjectDetails(project: Project, store: ProjectStore, runAction: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var capture by remember { mutableStateOf(false) }
    var target by remember(project.id) { mutableStateOf<Pair<Double, Double>?>(null) }
    var review by remember(project.id) { mutableStateOf<Review?>(null) }
    var deleteSession by remember { mutableStateOf<Session?>(null) }
    var exporting by remember { mutableStateOf(false) }
    if (capture) androidx.compose.ui.window.Dialog(onDismissRequest = { capture = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val captureContext = if (project.revision > 0) CaptureContext(store.directory(project.id), project.revision, project.mode == "180", target) else null
        Surface(Modifier.fillMaxSize()) { CaptureScreen(captureContext, onClose = { capture = false }, onAccept = { files ->
            capture = false
            val previous = Review("", project.revision, project.coverage)
            runAction {
                try { review = previous.copy(sessionId = store.import(project.id, files.map { android.net.Uri.fromFile(it) })) }
                finally { files.forEach { it.delete() } }
                PanoramaWorker.enqueue(context, project.id)
            }
        }) }
    }
    // Processing runs as a foreground worker; on Android 13+ its progress/cancel notification needs consent.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val sessions by store.dao.sessions(project.id).collectAsState(initial = emptyList())
    var assets by remember(project.id) { mutableStateOf(emptyList<Asset>()) }
    var frames by remember(project.id) { mutableStateOf(emptyList<Frame>()) }
    var history by remember { mutableStateOf(false) }
    var sphere by remember { mutableStateOf(true) }
    var showMask by remember { mutableStateOf(false) }
    var mapMessage by remember { mutableStateOf<String?>(null) }
    val jobs by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("panorama-${project.id}").collectAsState(initial = emptyList())
    val job = jobs.firstOrNull()
    var tapped by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    mapMessage?.let { text -> AlertDialog(onDismissRequest = { mapMessage = null },title = { Text("球面区域") },text = { Text(text) },confirmButton = { TextButton(onClick = { mapMessage = null }) { Text("确定") } },
        dismissButton = { tapped?.let { point -> TextButton(onClick = { target = point; mapMessage = null }) { Text("设为补拍目标") } } }) }
    review?.let { pendingReview ->
        val finished = job == null || job.state.isFinished
        if (finished && pendingReview.sessionId.isNotEmpty() && project.revision > pendingReview.revision && project.status == "处理完成") AlertDialog(onDismissRequest = {}, title = { Text("确认加入新批次") },
            text = { Text("覆盖率 %s → %s。可保留新结果，或撤销本批次并恢复之前版本。".format(pendingReview.coverage?.let { "%.2f%%".format(it) } ?: "—", project.coverage?.let { "%.2f%%".format(it) } ?: "—")) },
            confirmButton = { TextButton(onClick = { review = null }) { Text("保留") } },
            dismissButton = { TextButton(onClick = { review = null; runAction {
                if (pendingReview.revision > 0) store.restoreRevision(project.id, pendingReview.revision) else { store.dao.enableSession(pendingReview.sessionId, false); store.snapshot(project.id) }
            } }) { Text("撤销本批次") } })
        else if (finished && pendingReview.sessionId.isNotEmpty() && project.status == "处理失败") AlertDialog(onDismissRequest = { review = null }, title = { Text("新批次未能融合") },
            text = { Text("${project.error ?: ""}\n原结果已保留。可删除该批次，或保留素材后调整关键帧再试。") },
            confirmButton = { TextButton(onClick = { review = null; runAction { store.deleteSession(project.id, pendingReview.sessionId) } }) { Text("删除该批次") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("保留") } })
    }
    deleteSession?.let { session -> AlertDialog(onDismissRequest = { deleteSession = null }, title = { Text("删除此批次？") },
        text = { Text("将删除该批次在应用内的素材副本和关键帧，手机原始文件保留。删除后将重新生成全景。") },
        confirmButton = { TextButton(onClick = { deleteSession = null; runAction { store.deleteSession(project.id, session.id); PanoramaWorker.enqueue(context, project.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("取消") } }) }
    LaunchedEffect(project.id, project.status, job?.progress) { frames = withContext(Dispatchers.IO) { store.dao.frames(project.id) }; assets = withContext(Dispatchers.IO) { store.dao.assets(project.id) } }
    Text("未识别投影的视频按普通透视处理；明确标记的单目 ERP 自动选取最佳帧。", style = MaterialTheme.typography.labelSmall)
    val source = File(store.directory(project.id), "history/revision-${project.revision}/panorama.png")
    var exportOptions by remember { mutableStateOf<Exporter.Options?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> val options = exportOptions; if (uri != null && options != null) runAction {
        Exporter.export(context, source.parentFile!!, uri, options, project.mode, project.coverage)
    } }
    val jpegExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri -> val options = exportOptions; if (uri != null && options != null) runAction {
        Exporter.export(context, source.parentFile!!, uri, options, project.mode, project.coverage)
    } }
    if (exporting && source.exists()) ExportDialog(source, project, onDismiss = { exporting = false }) { options ->
        exporting = false; exportOptions = options
        val suffix = if (options.content == Exporter.Content.COVERAGE_MASK) "coverage" else "panorama-${options.width}"
        if (options.jpeg) jpegExport.launch("${project.name}-$suffix.jpg") else export.launch("${project.name}-$suffix.png")
    }
    val packageExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) runAction { Exporter.archive(store, project.id, uri) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { requestNotifications(); PanoramaWorker.enqueue(context, project.id) }) { Text("普通视频/照片拼接") }
        TextButton(onClick = { WorkManager.getInstance(context).cancelUniqueWork("panorama-${project.id}") }) { Text("取消处理") }
    }
    if (job != null && !job.state.isFinished) {
        Text("${job.progress.getString("stage") ?: "等待处理"} ${job.progress.getInt("done",0)}/${job.progress.getInt("total",0)}")
        val total = job.progress.getInt("total",0)
        if (total > 0) LinearProgressIndicator(progress = { job.progress.getInt("done",0).toFloat()/total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    project.coverage?.let { Text("实际球面覆盖 %.2f%%".format(it)) }
    val metrics = remember(project.revision) { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(File(source.parentFile, "metrics.json").readText()) as kotlinx.serialization.json.JsonObject }.getOrNull() }
    metrics?.let { m -> Text("本版本：%s 张关键帧 · 分析 %.1f s · 拼接 %.1f s · 进程峰值内存 %s MB".format(m["frames"], m["analysisMs"].toString().toDouble() / 1000, m["stitchMs"].toString().toDouble() / 1000,
        m["peakRssBytes"].toString().toLongOrNull()?.takeIf { it > 0 }?.let { (it / 1048576).toString() } ?: "未知"), style = MaterialTheme.typography.labelSmall) }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("恢复成功版本") }, text = {
        Column { File(store.directory(project.id),"history").listFiles()?.filter { File(it,"panorama.png").exists() }?.sortedByDescending { it.name.removePrefix("revision-").toIntOrNull() ?: 0 }?.forEach { file ->
            TextButton(onClick = { history = false;runAction { store.restoreRevision(project.id,file.name.removePrefix("revision-").toInt()) } }) { Text(file.name) }
        } }
    },confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
    if (source.exists()) {
        Row { TextButton(enabled = job == null || job.state.isFinished,onClick = { history = true }) { Text("历史") }; TextButton(onClick = { sphere = !sphere }) { Text(if (sphere) "展开图" else "球面浏览") }; TextButton(onClick = { showMask = !showMask; sphere = false }) { Text("覆盖 Mask") } }
        if (sphere) key(project.revision) { AndroidView(factory = { PanoramaView(it, source) }, modifier = Modifier.fillMaxWidth().height(210.dp)) }
        else {
            val preview = File(source.parentFile,"coverage-preview.png")
            val file = if (showMask) (if (preview.exists()) preview else File(source.parentFile,"coverage.png")) else source
            val bitmap = remember(project.revision, showMask) { BitmapFactory.decodeFile(file.absolutePath) }
            bitmap?.let { image -> Image(image.asImageBitmap(), "展开图：透明区域尚未拍摄", Modifier.fillMaxWidth().aspectRatio(2f).pointerInput(image) {
                detectTapGestures { offset ->
                    val x = (offset.x/size.width*image.width).toInt().coerceIn(0,image.width-1)
                    val y = (offset.y/size.height*image.height).toInt().coerceIn(0,image.height-1)
                    val mask = BitmapFactory.decodeFile(File(source.parentFile,"coverage.png").path)
                    val observed = mask?.let { android.graphics.Color.red(it.getPixel(x,y))>0 } ?: false
                    mask?.recycle()
                    tapped = if (observed) null else (x.toDouble()/image.width*360-180) to (90-y.toDouble()/image.height*180)
                    mapMessage = "水平 %.1f° / 垂直 %.1f°\n%s\n%s".format(x.toDouble()/image.width*360-180,90-y.toDouble()/image.height*180,if(observed) "已融合；绿色为清晰区域，黄色为低纹理或曝光风险" else "尚未融合",if(observed) "可补拍改善质量" else "请先对准已拍区域建立视觉重叠，再转向此方向")
                }
            }) }
        }
        Row {
            TextButton(onClick = { exporting = true }) { Text("导出图片") }
            TextButton(onClick = { packageExport.launch("${project.name}.panofusion.zip") }) { Text("工程包") }
        }
        var yaw by remember(project.referenceYaw) { mutableStateOf(project.referenceYaw.toFloat()) }
        Text("参考方向：水平 %.0f° 位于画面中心".format(yaw), style = MaterialTheme.typography.labelMedium)
        Slider(value = yaw, onValueChange = { yaw = (it / 5).roundToInt() * 5f }, valueRange = -180f..180f)
        if (yaw.toDouble() != project.referenceYaw) TextButton(enabled = job == null || job.state.isFinished, onClick = { runAction { store.setReferenceYaw(project.id, yaw.toDouble()); PanoramaWorker.enqueue(context, project.id) } }) { Text("应用参考方向并重新生成") }
    }
    target?.let { (y, p) -> Row { Text("补拍目标：水平 %.0f° 垂直 %.0f°".format(y, p), Modifier.padding(top = 12.dp)); TextButton(onClick = { target = null }) { Text("清除") } } }
    TextButton(onClick = { capture = true }) { Text(if (project.revision > 0) "补拍（视觉定位引导）" else "打开摄像头拍摄") }
    TextButton(enabled = job == null || job.state.isFinished,onClick = { runAction { store.dao.resetAnalysis(project.id); PanoramaWorker.enqueue(context,project.id) } }) { Text("按当前设置重新分析（重置帧选择）") }
    Text("素材批次 ${sessions.size} · 关键帧 ${frames.count { it.selected && it.enabled }}/${frames.size}")
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 350.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(sessions, key = { it.id }) { session -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.createdAt)))
                assets.filter { it.sessionId == session.id }.forEach { asset -> Text("${asset.displayName} · ${asset.mime}" + if(asset.width>0) " ${asset.width}×${asset.height}" else "", style = MaterialTheme.typography.labelSmall) }
            }
            Switch(checked = session.enabled, onCheckedChange = { enabled -> runAction {
                store.dao.enableSession(session.id, enabled); store.snapshot(project.id)
                PanoramaWorker.enqueue(context, project.id)
            } })
            TextButton(enabled = job == null || job.state.isFinished, onClick = { deleteSession = session }) { Text("删除") }
        } }
        items(frames, key = { it.id }) { frame ->
            Row(Modifier.fillMaxWidth()) {
                val thumbnail = remember(frame.path) { BitmapFactory.decodeFile(frame.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }
                thumbnail?.let { Image(it.asImageBitmap(), "关键帧", Modifier.size(64.dp)) }
                Column(Modifier.weight(1f)) { Text("${frame.timeUs/1000} ms · ${frame.reason}"); Text("清晰度 %.1f · 特征 %d".format(frame.sharpness, frame.features), style = MaterialTheme.typography.labelSmall) }
                if (frame.selected) Checkbox(checked = frame.enabled, onCheckedChange = { enabled -> runAction {
                    store.dao.enableFrame(frame.id, enabled)
                    frames = store.dao.frames(project.id)
                } })
            }
        }
    }
}
