package com.inyogeshwar.agentmt.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import com.inyogeshwar.agentmt.data.backup.BackupCrypto
import com.inyogeshwar.agentmt.data.backup.ExportOptions
import com.inyogeshwar.agentmt.data.backup.ImportPreview
import com.inyogeshwar.agentmt.data.backup.ImportResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LABELS = mapOf(
    "projects" to "Projects", "sessions" to "Chats & tasks", "messages" to "Messages", "runs" to "Runs", "providers" to "Providers",
    "credentials" to "Keys (labels only)", "routing_profiles" to "Routing profiles", "rules" to "Permission rules", "mcp_servers" to "MCP servers",
    "tags" to "Tags", "tag_links" to "Tag links", "plugins" to "Plugins", "usage" to "Usage history",
)

@Composable
fun BackupScreen(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    var chats by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf(true) }
    var attachments by remember { mutableStateOf(true) }
    var withKeys by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; message = null
        scope.launch {
            runCatching { c.backup.export(uri, ExportOptions(chats, files, attachments, pass.takeIf { withKeys })) }
                .onSuccess { r ->
                    message = true to buildString {
                        append("Backup saved (${r.bytes / 1024} KB). ")
                        append(r.counts.filterValues { it > 0 }.entries.joinToString { "${it.value} ${LABELS[it.key]?.lowercase() ?: it.key}" })
                        append(". ")
                        if (r.secrets > 0) append("${r.secrets} keys encrypted. ") else append("No keys included. ")
                        if (r.skippedProjects.isNotEmpty()) append("Not copied (outside AgentMT): ${r.skippedProjects.joinToString()}.")
                    }
                }
                .onFailure { message = false to (it.message ?: "Export failed") }
            busy = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }

    AppScaffold("Backup & restore", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text("Move your whole workspace to another phone: projects, chats, tasks, providers, routing, skills, plugins, MCP servers and settings, in one .zip.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            SectionLabel("Export")
            Card {
                Column(Modifier.padding(12.dp)) {
                    Check("Chats, tasks and run history", chats) { chats = it }
                    Check("Attachments", attachments && chats, enabled = chats) { attachments = it }
                    Check("Files of AgentMT projects", files) { files = it }
                    Check("API keys and tokens (encrypted)", withKeys) { withKeys = it }
                    if (withKeys) {
                        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (min 8 characters)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass2, { pass2 = it }, label = { Text("Repeat passphrase") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            isError = pass2.isNotEmpty() && pass2 != pass)
                        Text("Keys are encrypted with AES-256 using this passphrase. Without it they can't be read — not even by AgentMT.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(enabled = !busy && (!withKeys || (pass.length >= 8 && pass == pass2)), onClick = {
                        create.launch("agentmt-workspace-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip")
                    }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else { Icon(Icons.Outlined.Upload, null); Spacer(Modifier.size(8.dp)); Text("Export workspace") }
                    }
                }
            }
            message?.let { (ok, m) -> Text(m, color = if (ok) extra.success else extra.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            SectionLabel("Import")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Pick an AgentMT backup. You'll see what's inside before anything changes.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.size(8.dp)); Text("Import from file") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    importUri?.let { ImportDialog(c, it, onDone = { importUri = null; message = true to it }, onDismiss = { importUri = null }) }
}

@Composable
private fun Check(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange, enabled = enabled); Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Preview → options → confirm → result. Used by Settings and onboarding. */
@Composable
fun ImportDialog(c: AppContainer, uri: Uri, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var passError by remember { mutableStateOf(false) }
    val extra = LocalExtra.current

    androidx.compose.runtime.LaunchedEffect(uri) {
        runCatching { c.backup.preview(uri) }.onSuccess { preview = it }.onFailure { error = it.message ?: "Can't read this file" }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (result != null) "Workspace restored" else "Restore workspace?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = preview; val r = result
                when {
                    error != null -> Text(error!!, color = extra.danger)
                    r != null -> {
                        Text("Added: " + r.inserted.filterValues { it > 0 }.entries.joinToString { "${it.value} ${LABELS[it.key]?.lowercase() ?: it.key}" }.ifEmpty { "nothing new" })
                        if (r.skipped > 0) Text("Kept existing: ${r.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.secretsRestored > 0) Text("${r.secretsRestored} keys restored", color = extra.success)
                        if (r.keysNeeded > 0) Text("${r.keysNeeded} keys still need to be added under Providers.", color = extra.warning)
                        if (r.missingProjects.isNotEmpty()) Text("Folders not on this phone: ${r.missingProjects.joinToString()}. Open them again from the project picker.", color = extra.warning)
                    }
                    p == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Checking backup…") }
                    else -> {
                        Text("From ${p.device} · AgentMT ${p.appVersion} · ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(p.createdAt))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        p.counts.filterValues { it > 0 }.forEach { (t, n) ->
                            val d = p.duplicates[t] ?: 0
                            Text("• ${LABELS[t] ?: t}: $n" + if (d > 0) "  ($d already here)" else "")
                        }
                        if (p.projectFiles > 0) Text("• Project files: ${p.projectFiles}")
                        p.warnings.forEach { Text(it, color = extra.warning, style = MaterialTheme.typography.bodySmall) }
                        if (p.missingProjects.isNotEmpty()) Text("Not on this phone: ${p.missingProjects.joinToString()}", color = extra.warning, style = MaterialTheme.typography.bodySmall)
                        if (p.duplicates.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!replace, { replace = false }, label = { Text("Keep mine") })
                            FilterChip(replace, { replace = true }, label = { Text("Use backup's") })
                        }
                        if (p.hasSecrets) OutlinedTextField(pass, { pass = it }, label = { Text("Backup passphrase (for keys)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), isError = passError,
                            supportingText = { Text(if (passError) "Wrong passphrase — nothing was changed" else "Leave empty to skip keys") })
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = { onDone("Workspace restored.") }) { Text("Done") }
                preview != null && error == null -> TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { c.backup.import(preview!!, replace, pass.ifBlank { null }) }
                            .onSuccess { result = it; c.settings.update { s -> s.copy(onboarded = true) }; c.refreshPluginDirs() }
                            .onFailure { if (it is BackupCrypto.WrongPassphrase) passError = true else error = it.message ?: "Import failed" }
                        busy = false
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Restore") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { if (result == null && !busy) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
