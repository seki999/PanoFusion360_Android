package com.panofusion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@dagger.hilt.android.AndroidEntryPoint
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = androidx.lifecycle.ViewModelProvider(this)[ProjectViewModel::class.java]
        setContent { MaterialTheme(colorScheme = if(androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Surface(Modifier.fillMaxSize()) { PanoScreen(model) } } }
    }
}
@Composable
fun PanoScreen(model: ProjectViewModel) {
    val store = model.store
    val projects by model.projects.collectAsState(initial = emptyList())
    var current by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("360") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runAction(action: suspend () -> Unit) { model.execute(action) }
    LaunchedEffect(model) { model.errors.collect { message = it } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> current?.let { id -> if (uris.isNotEmpty()) runAction { store.import(id, uris) } } }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runAction { current = Exporter.restore(store, uri) } }
    val project = projects.firstOrNull { it.id == current }
    val scroll = rememberScrollState()
    @Composable fun modeLabel(mode: String) = stringResource(when (mode) { "360" -> R.string.mode_360; "180" -> R.string.mode_180; else -> R.string.mode_custom })
    Column(Modifier.fillMaxSize().safeDrawingPadding().then(if(project != null) Modifier.verticalScroll(scroll) else Modifier).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        if (project == null) {
            Row { TextButton(onClick = { settings = true }) { Text(stringResource(R.string.settings)) }; Button(onClick = { create = true }) { Text(stringResource(R.string.new_panorama)) }; TextButton(onClick = { importProject.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.import_project)) } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { p -> Card(Modifier.fillMaxWidth().clickable { current = p.id }) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val thumbnail = remember(p.id, p.revision) { if (p.revision > 0) android.graphics.BitmapFactory.decodeFile(java.io.File(store.directory(p.id), "history/revision-${p.revision}/panorama.png").path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }) else null }
                        thumbnail?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.width(120.dp).aspectRatio(2f)) }
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleLarge)
                            Text("${modeLabel(p.mode)} · ${p.status}")
                            Text(p.coverage?.let { stringResource(R.string.coverage_format, it) } ?: stringResource(R.string.coverage_unknown))
                            Text(stringResource(R.string.updated_format, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(p.updatedAt))), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } }
            }
        } else {
            Text(project.name, style = MaterialTheme.typography.headlineMedium)
            Text("${modeLabel(project.mode)} · ${project.status}")
            project.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row { TextButton(onClick = { current = null }) { Text(stringResource(R.string.back)) }; TextButton(onClick = { name = project.name;rename = true }) { Text(stringResource(R.string.rename)) }; TextButton(onClick = { runAction { current = store.duplicate(project.id) } }) { Text(stringResource(R.string.duplicate)) }; TextButton(onClick = { delete = true }) { Text(stringResource(R.string.delete_project)) } }
            Button(onClick = { picker.launch(arrayOf("video/*", "image/*")) }) { Text(stringResource(R.string.import_media)) }
            ProjectDetails(project, store, ::runAction)
        }
    }
    if (settings) SettingsDialog(store.context) { settings = false }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(stringResource(R.string.create_title)) }, text = {
        Column { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }); listOf("360", "180", "自定义").forEach { m -> Row(Modifier.clickable { mode = m }) { RadioButton(selected = mode == m, onClick = { mode = m }); Text(modeLabel(m), Modifier.padding(top = 12.dp)) } } }
    }, confirmButton = { TextButton(onClick = { create = false; runAction { current = store.create(name, mode).id } }) { Text(stringResource(R.string.create)) } }, dismissButton = { TextButton(onClick = { create = false }) { Text(stringResource(R.string.cancel)) } })
    if (rename && project != null) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename_title)) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }) }, confirmButton = { TextButton(onClick = { rename = false;runAction { store.rename(project.id,name) } }) { Text(stringResource(R.string.save)) } })
    if (delete && project != null) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_message)) }, confirmButton = { TextButton(onClick = { delete = false; current = null; runAction { store.delete(project.id) } }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text(stringResource(R.string.message_title)) }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok)) } }) }
}
