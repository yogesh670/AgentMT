package com.inyogeshwar.agentmt.feature.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.tools.Workspace
import com.inyogeshwar.agentmt.agent.tools.humanSize
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.relativeTime
import com.inyogeshwar.agentmt.core.ui.TagChip
import com.inyogeshwar.agentmt.core.ui.TagEditor
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import com.inyogeshwar.agentmt.feature.projects.allFilesAccessIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val CODE_EXT = setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "html", "css", "json", "xml", "gradle", "md", "yml", "yaml", "sh", "c", "cpp", "h", "rs", "go", "dart", "swift", "toml", "sql")
private val IMG_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(c: AppContainer, projectId: String, onBack: () -> Unit, onOpenFile: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<ProjectEntity?>(null) }
    var rel by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf<Boolean?>(null) } // true = folder
    var newName by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf<File?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val links by remember { c.db.tags().observeLinks() }.collectAsState(initial = emptyList())
    val known by remember { c.db.tags().observeCounts() }.collectAsState(initial = emptyList())
    val fileTags = remember(links) { links.filter { it.kind == "file" && it.ref.startsWith("$projectId:") }.groupBy({ it.ref.removePrefix("$projectId:") }, { it.tag }) }

    LaunchedEffect(projectId) { project = c.db.projects().get(projectId) }
    val ws = project?.let { Workspace(File(it.path)) }
    LaunchedEffect(ws?.root, rel, refresh) {
        if (ws == null) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            runCatching { ws.resolve(rel).listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }.getOrNull()
        }
    }

    AppScaffold(project?.name ?: "Files", onBack = { if (rel.isNotEmpty()) rel = rel.substringBeforeLast('/', "") else onBack() }, actions = {
        IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "Refresh") }
    }, floating = {
        if (entries != null) ExtendedFloatingActionButton(onClick = { create = false; newName = "" }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New") })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Breadcrumb
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val parts = listOf("" to (project?.name ?: "root")) + rel.split('/').filter { it.isNotEmpty() }.runningReduce { a, b -> "$a/$b" }.map { it to it.substringAfterLast('/') }
                parts.forEachIndexed { i, (path, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (i == parts.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { rel = path }).padding(6.dp))
                    if (i < parts.size - 1) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                project == null -> Unit
                entries == null -> EmptyState(Icons.Outlined.FolderOff, "Can't open this folder",
                    "AgentMT doesn't have permission to read ${project?.path}. Turn on All files access, or use an AgentMT project.") {
                    Button(onClick = { ctx.startActivity(allFilesAccessIntent(ctx.packageName)) }) { Text("Open settings") }
                }
                tagFilter != null -> LazyColumn(Modifier.fillMaxSize()) {
                    val hits = fileTags.filterValues { tagFilter in it }.keys.sorted()
                    item { Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagChip(tagFilter!!); Spacer(Modifier.width(8.dp)); Text("${hits.size} files", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f)); TextButton(onClick = { tagFilter = null }) { Text("Clear") } } }
                    items(hits) { path -> Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenFile(path) }).padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp))
                        Text(path, style = MaterialTheme.typography.bodyLarge) } }
                }
                entries!!.isEmpty() -> EmptyState(Icons.Outlined.Folder, "Empty folder", "Ask the agent to create files here, or tap New.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries!!, key = { it.path }) { f ->
                        Box {
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { if (f.isDirectory) rel = (if (rel.isEmpty()) f.name else "$rel/${f.name}") else onOpenFile(ws!!.rel(f)) },
                                    onLongClick = { menuFor = f },
                                ).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val ext = f.extension.lowercase()
                                Icon(
                                    when { f.isDirectory -> Icons.Outlined.Folder; ext in IMG_EXT -> Icons.Outlined.Image; ext in CODE_EXT -> Icons.Outlined.Code; else -> Icons.Outlined.Description },
                                    null, tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        fileTags[ws!!.rel(f)].orEmpty().take(3).forEach { t -> TagChip(t, Modifier.padding(start = 6.dp)) { tagFilter = t } }
                                    }
                                    Text(
                                        if (f.isDirectory) "${f.list()?.size ?: 0} items" else "${humanSize(f.length())} · ${relativeTime(f.lastModified())}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(menuFor == f, { menuFor = null }) {
                                DropdownMenuItem({ Text("Rename") }, onClick = { menuFor = null; renaming = f; newName = f.name })
                                DropdownMenuItem({ Text("Tags…") }, onClick = { menuFor = null; tagging = f })
                                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menuFor = null; deleting = f })
                            }
                        }
                    }
                }
            }
        }
    }

    create?.let { folder ->
        AlertDialog(
            onDismissRequest = { create = null },
            title = { Text("New ${if (folder) "folder" else "file"}") },
            text = {
                Column {
                    Row { TextButton(onClick = { create = false }) { Text("File") }; TextButton(onClick = { create = true }) { Text("Folder") } }
                    OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(if (folder) "Folder name" else "File name, e.g. index.html") })
                }
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank() && !newName.contains(".."), onClick = {
                    val w = ws ?: return@TextButton
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val f = w.resolve(if (rel.isEmpty()) newName.trim() else "$rel/${newName.trim()}")
                            if (f.exists()) error("Already exists")
                            if (folder) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
                        }.onFailure { error = it.message }
                        refresh++
                    }
                    create = null
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { create = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { f ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = newName.isNotBlank() && !newName.contains('/'), onClick = {
                val oldRel = ws?.rel(f)
                val dest = File(f.parentFile, newName.trim())
                if (!f.renameTo(dest)) error = "Couldn't rename ${f.name}"
                else if (oldRel != null) scope.launch { c.db.tags().moveRef("file", "$projectId:$oldRel", "$projectId:${ws!!.rel(dest)}") }
                renaming = null; refresh++
            }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { f ->
        ConfirmDialog("Delete ${f.name}?", if (f.isDirectory) "The folder and everything inside it will be permanently deleted." else "This file will be permanently deleted.",
            "Delete", true, onConfirm = { scope.launch(Dispatchers.IO) { f.deleteRecursively(); refresh++ } }, onDismiss = { deleting = null })
    }
    tagging?.let { f ->
        val rel = ws?.rel(f) ?: return@let
        TagEditor(fileTags[rel].orEmpty(), known.map { it.name },
            onAdd = { t -> scope.launch { c.addTag(t, "file", "$projectId:$rel") } },
            onRemove = { t -> scope.launch { c.removeTag(t, "file", "$projectId:$rel") } }) { tagging = null }
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("Something went wrong") }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}
