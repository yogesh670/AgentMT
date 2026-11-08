package com.inyogeshwar.agentmt.feature.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.plugins.MarketplaceItem
import com.inyogeshwar.agentmt.agent.plugins.PluginSummary
import com.inyogeshwar.agentmt.agent.skills.RemoteEntry
import com.inyogeshwar.agentmt.agent.skills.RepoRef
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.data.db.PluginEntity
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import kotlinx.coroutines.launch
import java.io.File

private val FEATURED_SKILL_REPOS = listOf("anthropics/skills", "inyogeshwar/microtool-launch-operator-skill")
private val FEATURED_PLUGIN_REPOS = listOf("anthropics/claude-plugins-official")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstallSkillsDialog(c: AppContainer, projects: List<ProjectEntity>, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Pair<RepoRef, List<RemoteEntry>>?>(null) }
    var target by remember { mutableStateOf<ProjectEntity?>(null) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    val extra = LocalExtra.current

    fun search(text: String) {
        val parsed = RepoRef.parse(text) ?: run { error = "Use owner/repo, a GitHub URL or a skills.sh link"; return }
        // skills.sh/<owner>/<repo>/<skill> names a skill, not a path: search the repo and filter by name.
        val viaSkillsSh = text.contains("skills.sh/") && parsed.path.isNotEmpty() && !parsed.path.contains('/')
        val ref = if (viaSkillsSh) parsed.copy(path = "") else parsed
        val nameFilter = if (viaSkillsSh) parsed.path else null
        busy = true; error = null; found = null
        scope.launch {
            runCatching { c.github.findSkills(ref).filter { nameFilter == null || it.name.equals(nameFilter, true) } }
                .onSuccess { found = ref to it; if (it.isEmpty()) error = "No SKILL.md folders found there." }
                .onFailure { error = it.message }
            busy = false
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Install skills") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("GitHub repo or skills.sh link") },
                placeholder = { Text("owner/repo/path") }, trailingIcon = { TextButton(enabled = !busy && input.isNotBlank(), onClick = { search(input) }) { Text("Find") } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FEATURED_SKILL_REPOS.forEach { r -> FilterChip(false, { input = r; search(r) }, label = { Text(r, fontFamily = Mono) }) }
            }
            Text("Install to", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(target == null, { target = null }, label = { Text("All projects") })
                projects.take(6).forEach { p -> FilterChip(target?.id == p.id, { target = p }, label = { Text(p.name) }) }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Searching GitHub…") }
            error?.let { Text(it, color = extra.danger, style = MaterialTheme.typography.bodySmall) }
            found?.let { (ref, list) ->
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.name, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (e.path in installed) Text("Installed", color = extra.success, style = MaterialTheme.typography.labelMedium)
                            else TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    runCatching {
                                        val files = c.github.downloadTree(ref, e.path)
                                        val body = files["SKILL.md"]?.let { String(it) } ?: error("SKILL.md missing")
                                        val skill = SkillStore.parse(body, e.name, "Yours", "")!!
                                        c.skills.save(skill.name, body, target?.let { File(it.path) }, files - "SKILL.md")
                                        skill.name
                                    }.onSuccess { installed = installed + e.path; onDone("Installed skill $it") }.onFailure { error = it.message }
                                    busy = false
                                }
                            }) { Text("Install") }
                        }
                    }
                }
            }
            Text("Only Markdown and supporting files are copied. Anything a skill asks the agent to run still goes through your approvals.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstallPluginDialog(c: AppContainer, onDone: (PluginEntity) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var market by remember { mutableStateOf<Pair<RepoRef, List<MarketplaceItem>>?>(null) }
    var q by remember { mutableStateOf("") }
    val extra = LocalExtra.current
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch { runCatching { c.plugins.installFromZip(uri) }.onSuccess { onDone(it); onDismiss() }.onFailure { error = it.message }; busy = false }
    }
    fun install(ref: RepoRef) {
        busy = true; error = null
        scope.launch {
            runCatching {
                val m = c.plugins.marketplace(ref)
                if (m != null) { market = ref to m; null } else c.plugins.installFromGitHub(ref)
            }.onSuccess { it?.let { p -> onDone(p); onDismiss() } }.onFailure { error = it.message }
            busy = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Install plugin") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("GitHub repo, plugin folder or marketplace") },
                trailingIcon = { TextButton(enabled = !busy && input.isNotBlank(), onClick = { RepoRef.parse(input)?.let(::install) ?: run { error = "Use owner/repo or a GitHub URL" } }) { Text("Go") } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FEATURED_PLUGIN_REPOS.forEach { r -> FilterChip(false, { input = r; install(RepoRef.parse(r)!!) }, label = { Text(r, fontFamily = Mono) }) }
            }
            OutlinedButton(onClick = { zip.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Install from ZIP file") }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Working…") }
            error?.let { Text(it, color = extra.danger, style = MaterialTheme.typography.bodySmall) }
            market?.let { (ref, items) ->
                Text("${items.size} plugins in ${ref.display}", style = MaterialTheme.typography.labelLarge)
                if (items.size > 8) OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Filter") })
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    items.filter { q.isBlank() || it.name.contains(q, true) || it.description.contains(q, true) }.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            TextButton(enabled = !busy, onClick = { install(ref.copy(path = m.path)) }) { Text("Install") }
                        }
                    }
                }
            }
            Text("Plugins install switched off. You'll see what each one adds before turning it on.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun PluginReviewDialog(p: PluginEntity, onEnable: () -> Unit, onDisable: () -> Unit, onUninstall: () -> Unit, onDismiss: () -> Unit) {
    val s = PluginSummary.parse(p.summaryJson)
    val extra = LocalExtra.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${p.name} ${p.version}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
            Text("Source: ${p.source}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("When enabled it adds:", style = MaterialTheme.typography.labelLarge)
            Text("• Skills: ${s.skills.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            Text("• Slash commands: ${s.commands.joinToString { "/$it" }.ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            Text("• MCP servers (network): ${s.mcp.joinToString { "${it.first} → ${it.second}" }.ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            if (s.unsupported.isNotEmpty()) Text("Ignored on Android: ${s.unsupported.joinToString()}", color = extra.warning, style = MaterialTheme.typography.bodySmall)
            Text("Plugins can't run code here. Their MCP tool calls still need your approval.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        if (p.enabled) TextButton(onClick = onDisable) { Text("Turn off") } else TextButton(onClick = onEnable) { Text("Enable") }
    }, dismissButton = { TextButton(onClick = onUninstall) { Text("Uninstall", color = MaterialTheme.colorScheme.error) } })
}
