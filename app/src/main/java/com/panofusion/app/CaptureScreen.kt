package com.panofusion.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Inputs for relocalising the camera against the last committed stitched revision. */
data class CaptureContext(val projectDirectory: File, val revision: Int, val half: Boolean, val target: Pair<Double, Double>?)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(captureContext: CaptureContext?, onClose: () -> Unit, onAccept: (List<File>) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var photo by remember { mutableStateOf<ImageCapture?>(null) }
    var video by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    val captured = remember { mutableStateListOf<File>() }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf(0f) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // Localisation state. A pose is only shown when it comes from verified feature matches.
    var pose by remember { mutableStateOf<VisualPose?>(null) }
    var poseAge by remember { mutableStateOf(0L) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var showOverlay by remember { mutableStateOf(true) }
    var localizerMessage by remember { mutableStateOf(if (captureContext == null) "尚无拼接结果，无法视觉定位" else "正在载入关键帧…") }
    var recommended by remember { mutableStateOf(captureContext?.target) }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val worker = remember { Dispatchers.Default.limitedParallelism(1) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object: SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9);val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix,event.values)
                SensorManager.getOrientation(matrix,orientation)
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            }
        }
        if (sensor != null) manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener); recording?.stop() }
    }
    LaunchedEffect(captureContext, permission) {
        if (captureContext == null || !permission) return@LaunchedEffect
        val opened = withContext(worker) { runCatching { VisualLocalizer.open(captureContext.projectDirectory, captureContext.revision) } }
        val localizer = opened.getOrElse { localizerMessage = "无法视觉定位：${it.message}"; return@LaunchedEffect }
        val grid = withContext(Dispatchers.IO) { CoverageGrid.fromMask(File(captureContext.projectDirectory, "history/revision-${captureContext.revision}/coverage.png")) }
        val query = File(context.cacheDir, "localize-query.jpg")
        val overlayFile = File(context.cacheDir, "localize-overlay.png")
        localizerMessage = "尚未定位 · 请先对准已经拍摄过的区域，以便系统识别当前位置。"
        try {
            while (isActive) {
                delay(1200)
                val view = previewView ?: continue
                val frame = view.bitmap ?: continue
                val scale = minOf(1f, 800f / maxOf(frame.width, frame.height))
                val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
                val result = withContext(worker) {
                    try {
                        query.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        val (found, rendered) = localizer.localize(query, overlayFile, small.width, small.height)
                        found to if (rendered) BitmapFactory.decodeFile(overlayFile.path)?.asImageBitmap() else null
                    } finally { if (small !== frame) small.recycle(); frame.recycle() }
                }
                if (result.first != null) {
                    pose = result.first; poseAge = System.currentTimeMillis(); overlay = result.second
                    val p = result.first!!
                    if (captureContext.target == null && grid != null) recommended = grid.recommend(p.yaw, p.pitch, captureContext.half)
                    localizerMessage = "已视觉定位 · 水平 %.0f° 垂直 %.0f° · %d 个几何内点".format(p.yaw, p.pitch, p.inliers)
                } else if (System.currentTimeMillis() - poseAge > 3000) {
                    pose = null; overlay = null
                    localizerMessage = "尚未定位 · 无法自动匹配当前画面。请先拍摄一部分已完成区域，再缓慢转向缺失区域。"
                }
            }
        } finally { withContext(NonCancellable + worker) { localizer.close() } }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton(onClick = onClose) { Text("返回工程") }; Text("拍摄 / 补拍", style = MaterialTheme.typography.titleLarge) }
        Text(localizerMessage, style = MaterialTheme.typography.bodyMedium)
        val current = pose
        val target = recommended
        Text(when {
            current != null && target != null -> "目标 水平 %.0f° 垂直 %.0f° → %s".format(target.first, target.second, Guidance.instruction(current.yaw, current.pitch, target.first, target.second))
            current != null -> "当前目标区域已覆盖"
            else -> "传感器俯仰 %.0f°（仅作辅助，未定位前不代表全景方向）".format(pitch)
        })
        if (!permission) Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("允许使用摄像头") }
        else Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx ->
                PreviewView(ctx).also { view ->
                    // TextureView mode keeps PreviewView.bitmap available for localisation.
                    view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = view
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
                            val vc = VideoCapture.withOutput(recorder)
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview,capture,vc)
                            photo = capture;video = vc
                        } catch (e: Exception) { error = "摄像头初始化失败：${e.message}" }
                    },executor)
                }
            }, modifier = Modifier.fillMaxSize())
            if (showOverlay && current != null) overlay?.let { Image(it, "已拍摄区域半透明叠加", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = 0.45f) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("录制 ${elapsed/1_000_000_000}s · 本次已拍 ${captured.size} 项 · 围绕固定位置缓慢旋转，保持相邻画面重叠。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = permission && recording == null && photo != null, onClick = {
                if (context.cacheDir.usableSpace < 64L*1024*1024) { error = "存储空间不足，请至少保留 64 MB"; return@Button }
                val file = File(context.cacheDir,"capture-${UUID.randomUUID()}.jpg")
                photo?.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object: ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) { captured += file }
                    override fun onError(exception: ImageCaptureException) { error = exception.message;file.delete() }
                })
            }) { Text("拍照") }
            Button(enabled = permission && video != null, onClick = {
                if (recording != null) { recording?.stop();recording = null }
                else {
                    if (context.cacheDir.usableSpace < 256L*1024*1024) error = "存储空间不足，请至少保留 256 MB" else {
                        val file = File(context.cacheDir,"capture-${UUID.randomUUID()}.mp4")
                        recording = video?.output?.prepareRecording(context,FileOutputOptions.Builder(file).build())?.start(executor) { event ->
                            elapsed = event.recordingStats.recordedDurationNanos
                            if (event is VideoRecordEvent.Status && (elapsed > 180_000_000_000L || context.cacheDir.usableSpace < 64L*1024*1024)) recording?.stop()
                            if (event is VideoRecordEvent.Finalize) { recording = null; if (event.hasError()) { error = "录像失败：${event.error}";file.delete() } else captured += file }
                        }
                    }
                }
            }) { Text(if (recording == null) "录像" else "停止") }
            if (camera?.cameraInfo?.hasFlashUnit() == true) TextButton(onClick = { torch = !torch;camera?.cameraControl?.enableTorch(torch) }) { Text("补光") }
            TextButton(onClick = { showOverlay = !showOverlay }) { Text(if (showOverlay) "隐藏叠加" else "显示叠加") }
        }
        if (captured.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = recording == null, onClick = { onAccept(captured.toList()); captured.clear() }) { Text("完成拍摄（${captured.size} 项作为新批次）") }
            TextButton(enabled = recording == null, onClick = { captured.forEach { it.delete() }; captured.clear() }) { Text("全部丢弃") }
        }
    }
}
