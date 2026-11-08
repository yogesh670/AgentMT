package com.inyogeshwar.agentmt.feature.projects

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.treeUriToPath
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import kotlinx.coroutines.launch
import java.io.File

fun allFilesAccessIntent(pkg: String): Intent =
    if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    container: AppContainer,
    projects: List<ProjectEntity>,
    selectedId: String?,
    onPick: (ProjectEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf<String?>(null) }
    var needAccess by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path == null || !File(path).isDirectory) { error = "That folder can't be opened as a project. Pick a folder on internal storage."; return@rememberLauncherForActivityResult }
        scope.launch { onPick(container.openProject(path)); onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Project", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text("The agent can only read and change files inside this folder.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            ListRow("New project", "A fresh folder inside AgentMT. No permission needed.", Icons.Outlined.CreateNewFolder,
                iconTint = MaterialTheme.colorScheme.primary, onClick = { newName = "" }, showChevron = false)
            if (com.inyogeshwar.agentmt.BuildConfig.FULL) ListRow("Open a folder on this phone", if (container.hasAllFilesAccess()) "Pick any folder from internal storage" else "Needs All files access",
                Icons.Outlined.FolderOpen, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { if (container.hasAllFilesAccess()) picker.launch(null) else needAccess = true }, showChevron = false)
            if (com.inyogeshwar.agentmt.BuildConfig.FULL) ListRow("Paste a folder path", "Like /storage/emulated/0/MT2/apks/MyClone (copy it from MT Manager or a file manager)",
                Icons.Outlined.Edit, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { if (container.hasAllFilesAccess()) pathInput = "" else needAccess = true }, showChevron = false)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxWidth().height((projects.size.coerceIn(1, 6) * 68).dp)) {
                items(projects, key = { it.id }) { p ->
                    ListRow(p.name, p.path.replace("/storage/emulated/0", "Internal storage"), Icons.Outlined.Folder,
                        trailing = { if (p.id == selectedId) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onPick(p); onDismiss() })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    newName?.let { name ->
        AlertDialog(
            onDismissRequest = { newName = null },
            title = { Text("New project") },
            text = { OutlinedTextField(name, { newName = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { onPick(container.createPrivateProject(name)); newName = null; onDismiss() }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("Cancel") } },
        )
    }
    pathInput?.let { raw ->
        AlertDialog(
            onDismissRequest = { pathInput = null },
            title = { Text("Open folder by path") },
            text = { OutlinedTextField(raw, { pathInput = it }, label = { Text("Folder path") }, singleLine = true,
                placeholder = { Text("/storage/emulated/0/MyProject") }, supportingText = { Text("A missing folder is created.") }) },
            confirmButton = {
                TextButton(enabled = raw.trim().startsWith("/"), onClick = {
                    val path = raw.trim().replace(Regex("^/sdcard(?=/|$)"), "/storage/emulated/0").trimEnd('/')
                    val dir = File(path)
                    if (!dir.isDirectory) dir.mkdirs()
                    if (!dir.isDirectory || !dir.canRead()) { error = "Can't open $path. Check the path and All files access." }
                    else scope.launch { onPick(container.openProject(path)); pathInput = null; onDismiss() }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { pathInput = null }) { Text("Cancel") } },
        )
    }
    if (needAccess) AlertDialog(
        onDismissRequest = { needAccess = false },
        title = { Text("Allow All files access") },
        text = { Text("To work on folders anywhere on your phone (like Download/MyApp), Android needs you to turn on All files access for AgentMT. You can still use AgentMT projects without it.") },
        confirmButton = { TextButton(onClick = { needAccess = false; ctx.startActivity(allFilesAccessIntent(ctx.packageName)) }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { needAccess = false }) { Text("Not now") } },
    )
    error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("Can't open folder") }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}
