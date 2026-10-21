package com.panofusion.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

@Composable
fun CaptureScreen(onClose: () -> Unit, onAccept: (File) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var photo by remember { mutableStateOf<ImageCapture?>(null) }
    var video by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf(0f) }
    val executor = remember { ContextCompat.getMainExecutor(context) }
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton(onClick = onClose) { Text("返回工程") }; Text("拍摄 / 补拍", style = MaterialTheme.typography.titleLarge) }
        Text("尚未视觉定位 · 请先拍摄已完成区域，再缓慢转向缺失区域。")
        Text("传感器俯仰 %.0f°（辅助读数）".format(pitch))
        if (!permission) Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("允许使用摄像头") }
        else AndroidView(factory = { ctx ->
            PreviewView(ctx).also { view ->
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
        }, modifier = Modifier.fillMaxWidth().weight(1f))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("录制时间 ${elapsed/1_000_000_000}s · 先绕固定位置缓慢旋转，保持相邻画面重叠。")
        Row {
            Button(enabled = permission && recording == null && pending == null && photo != null, onClick = {
                val file = File(context.cacheDir,"capture-${UUID.randomUUID()}.jpg")
                photo?.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object: ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) { pending = file }
                    override fun onError(exception: ImageCaptureException) { error = exception.message;file.delete() }
                })
            }) { Text("拍照") }
            Button(enabled = permission && pending == null && video != null, onClick = {
                if (recording != null) { recording?.stop();recording = null }
                else {
                    if (context.cacheDir.usableSpace < 256L*1024*1024) error = "存储空间不足，请至少保留 256 MB" else {
                        val file = File(context.cacheDir,"capture-${UUID.randomUUID()}.mp4")
                        recording = video?.output?.prepareRecording(context,FileOutputOptions.Builder(file).build())?.start(executor) { event ->
                            elapsed = event.recordingStats.recordedDurationNanos
                            if (event is VideoRecordEvent.Status && (elapsed > 180_000_000_000L || context.cacheDir.usableSpace < 64L*1024*1024)) recording?.stop()
                            if (event is VideoRecordEvent.Finalize) { recording = null; if (event.hasError()) { error = "录像失败：${event.error}";file.delete() } else pending = file }
                        }
                    }
                }
            }) { Text(if (recording == null) "录像" else "停止") }
            if (camera?.cameraInfo?.hasFlashUnit() == true) TextButton(onClick = { torch = !torch;camera?.cameraControl?.enableTorch(torch) }) { Text("补光") }
        }
        pending?.let { file ->
            Text("素材已保存，确认后作为新批次加入工程。")
            Row { Button(onClick = { onAccept(file);pending = null }) { Text("加入工程") }; TextButton(onClick = { file.delete();pending = null }) { Text("丢弃重拍") } }
        }
    }
}

