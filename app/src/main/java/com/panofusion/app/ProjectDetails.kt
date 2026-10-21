package com.panofusion.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
fun ProjectDetails(project: Project, store: ProjectStore, runAction: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var capture by remember { mutableStateOf(false) }
    if (capture) androidx.compose.ui.window.Dialog(onDismissRequest = { capture = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { CaptureScreen(onClose = { capture = false }, onAccept = { file ->
            capture = false
            runAction { store.import(project.id, listOf(android.net.Uri.fromFile(file))); file.delete() }
        }) }
    }
    val sessions by store.dao.sessions(project.id).collectAsState(initial = emptyList())
    var frames by remember(project.id) { mutableStateOf(emptyList<Frame>()) }
    var sphere by remember { mutableStateOf(true) }
    var showMask by remember { mutableStateOf(false) }
    var exportJpeg by remember { mutableStateOf(false) }
    val jobs by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("panorama-${project.id}").collectAsState(initial = emptyList())
    val job = jobs.firstOrNull()
    LaunchedEffect(project.id, project.status, job?.progress) { frames = withContext(Dispatchers.IO) { store.dao.frames(project.id) } }
    Text("未识别投影的视频按普通透视处理；明确标记的单目 ERP 自动选取最佳帧。", style = MaterialTheme.typography.labelSmall)
    val source = File(store.directory(project.id), "history/revision-${project.revision}/panorama.png")
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) runAction {
        context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out); if (showMask) File(source.parentFile, "coverage.png").inputStream().use { it.copyTo(out) } else source.inputStream().use { it.copyTo(out) } }
    } }
    val jpegExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri -> if (uri != null) runAction {
        Exporter.image(context, source, uri, true)
    } }
    val packageExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) runAction { Exporter.archive(store, project.id, uri) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { PanoramaWorker.enqueue(context, project.id) }) { Text("普通视频/照片拼接") }
        TextButton(onClick = { WorkManager.getInstance(context).cancelUniqueWork("panorama-${project.id}") }) { Text("取消处理") }
    }
    if (job != null && !job.state.isFinished) {
        Text("${job.progress.getString("stage") ?: "等待处理"} ${job.progress.getInt("done",0)}/${job.progress.getInt("total",0)}")
        val total = job.progress.getInt("total",0)
        if (total > 0) LinearProgressIndicator(progress = { job.progress.getInt("done",0).toFloat()/total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    project.coverage?.let { Text("实际球面覆盖 %.2f%%".format(it)) }
    if (source.exists()) {
        Row { TextButton(onClick = { sphere = !sphere }) { Text(if (sphere) "展开图" else "球面浏览") }; TextButton(onClick = { showMask = !showMask; sphere = false }) { Text("覆盖 Mask") } }
        if (sphere) key(project.revision) { AndroidView(factory = { PanoramaView(it, source) }, modifier = Modifier.fillMaxWidth().height(210.dp)) }
        else {
            val file = if (showMask) File(source.parentFile,"coverage.png") else source
            val bitmap = remember(project.revision, showMask) { BitmapFactory.decodeFile(file.absolutePath) }
            bitmap?.let { Image(it.asImageBitmap(), "展开图：透明区域尚未拍摄", Modifier.fillMaxWidth().height(150.dp)) }
        }
        Row {
            TextButton(onClick = { export.launch(if (showMask) "coverage.png" else "panorama.png") }) { Text("导出 PNG") }
            TextButton(onClick = { jpegExport.launch("panorama.jpg") }) { Text("JPEG") }
            TextButton(onClick = { packageExport.launch("${project.name}.panofusion.zip") }) { Text("工程包") }
        }
        Text("普通视频原生 2K；ERP 保留解码尺寸。JPEG 缺失区域深灰。", style = MaterialTheme.typography.labelSmall)
    }
    TextButton(onClick = { capture = true }) { Text("打开摄像头继续拍摄") }
    Text("素材批次 ${sessions.size} · 关键帧 ${frames.count { it.selected && it.enabled }}/${frames.size}")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(sessions, key = { it.id }) { session -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.createdAt)))
            Switch(checked = session.enabled, onCheckedChange = { enabled -> runAction {
                store.dao.enableSession(session.id, enabled); store.snapshot(project.id)
                PanoramaWorker.enqueue(context, project.id)
            } })
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
