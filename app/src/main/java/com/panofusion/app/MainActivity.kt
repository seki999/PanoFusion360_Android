package com.panofusion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { PanoScreen((application as PanoApplication).store) } } }
    }
}
@Composable
fun PanoScreen(store: ProjectStore) {
    val projects by store.dao.observe().collectAsState(initial = emptyList())
    var current by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("360") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runAction(action: suspend () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { action() } } catch (e: Exception) { message = e.message ?: "操作失败" } } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> current?.let { id -> if (uris.isNotEmpty()) runAction { store.import(id, uris) } } }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runAction { current = Exporter.restore(store, uri) } }
    val project = projects.firstOrNull { it.id == current }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PanoFusion 360", style = MaterialTheme.typography.headlineLarge)
        Text("离线全景 · 保留每一次拍摄", style = MaterialTheme.typography.bodyMedium)
        if (project == null) {
            Row { Button(onClick = { create = true }) { Text("新建全景") }; TextButton(onClick = { importProject.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入工程包") } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { p -> Card(Modifier.fillMaxWidth().clickable { current = p.id }) {
                    Column(Modifier.padding(16.dp)) { Text(p.name, style = MaterialTheme.typography.titleLarge); Text("${p.mode}° · ${p.status}"); Text(p.coverage?.let { "球面覆盖 %.2f%%".format(it) } ?: "尚未计算覆盖率") }
                } }
            }
        } else {
            Text(project.name, style = MaterialTheme.typography.headlineMedium)
            Text(project.status)
            project.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row { TextButton(onClick = { current = null }) { Text("返回") }; TextButton(onClick = { delete = true }) { Text("删除工程") } }
            Button(onClick = { picker.launch(arrayOf("video/*", "image/*")) }) { Text("导入视频 / 照片（新补拍批次）") }
            ProjectDetails(project, store, ::runAction)
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建全景工程") }, text = {
        Column { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }); listOf("360", "180", "自定义").forEach { m -> Row(Modifier.clickable { mode = m }) { RadioButton(selected = mode == m, onClick = { mode = m }); Text(m, Modifier.padding(top = 12.dp)) } } }
    }, confirmButton = { TextButton(onClick = { create = false; runAction { current = store.create(name, mode).id } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("取消") } })
    if (delete && project != null) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除此工程？") }, text = { Text("删除应用内素材副本及结果。手机原始文件保留。") }, confirmButton = { TextButton(onClick = { delete = false; current = null; runAction { store.delete(project.id) } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("操作提示") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("确定") } }) }
}
