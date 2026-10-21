package com.panofusion.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.preferences by preferencesDataStore("processing-settings")
data class ProcessingSettings(val sharpness: Double = 35.0, val samplingUs: Long = 500_000, val outputWidth: Int = 2048)
object SettingsStore {
    private val sharpness = doublePreferencesKey("minimum-sharpness")
    private val sampling = longPreferencesKey("sampling-us")
    private val width = intPreferencesKey("output-width")
    fun flow(context: Context): Flow<ProcessingSettings> = context.preferences.data.map { ProcessingSettings(it[sharpness] ?: 35.0,it[sampling] ?: 500_000,it[width] ?: 2048) }
    suspend fun save(context: Context, settings: ProcessingSettings) {
        require(settings.sharpness in 0.0..1000.0 && settings.samplingUs in 250_000..2_000_000 && settings.outputWidth in listOf(2048,4096))
        context.preferences.edit { it[sharpness] = settings.sharpness;it[sampling] = settings.samplingUs;it[width] = settings.outputWidth }
    }
}
@Composable
fun SettingsDialog(context: Context,onClose: () -> Unit) {
    val current by SettingsStore.flow(context).collectAsState(initial = ProcessingSettings())
    var value by remember(current) { mutableStateOf(current) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose,title = { Text("处理设置") },text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("最低清晰度 %.0f（Laplacian 方差）".format(value.sharpness))
            Slider(value = value.sharpness.toFloat(),onValueChange = { value=value.copy(sharpness=it.toDouble()) },valueRange=0f..150f)
            Text("候选采样间隔 ${value.samplingUs/1000} ms")
            Slider(value = value.samplingUs.toFloat(),onValueChange = { value=value.copy(samplingUs=(it/1000).toLong()*1000) },valueRange=250_000f..2_000_000f)
            Text("原生输出宽度（非结果放大）")
            Row { listOf(2048,4096).forEach { w -> FilterChip(selected=value.outputWidth==w,onClick={value=value.copy(outputWidth=w)},label={Text("${w}px")}) } }
            Text("4K 消耗更多内存。工作帧仍限制分辨率，最终细节受输入与工作帧限制。")
        }
    },confirmButton = { TextButton(onClick = { scope.launch { SettingsStore.save(context,value);onClose() } }) { Text("保存") } },dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}
