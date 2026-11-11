package com.inyogeshwar.agentmt.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.mcp.McpClient
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.data.db.McpServerEntity
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun McpEditScreen(c: AppContainer, id: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sid = remember { id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf(0L) }
    var confirmDelete by remember { mutableStateOf(false) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var pluginId by remember { mutableStateOf<String?>(null) }
    val projects by remember { c.db.projects().observeAll() }.collectAsState(initial = emptyList())
    val extra = LocalExtra.current

    LaunchedEffect(id) {
        if (id != null) c.db.mcp().get(id)?.let {
            name = it.name; url = it.url; enabled = it.enabled; tools = it.toolsCache.lines().filter { l -> l.isNotBlank() }; created = it.createdAt
            projectId = it.projectId; pluginId = it.pluginId
            hasToken = c.vault.has("mcp:$id")
        }
    }
    fun tokenNow() = token.trim().ifEmpty { c.vault.get("mcp:$sid") }

    AppScaffold(if (id == null) "Add MCP server" else name, onBack, actions = {
        if (id != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Remote servers (Streamable HTTP), or a local stdio server that runs inside Linux: write linux: and the command, e.g. linux:npx -y rea-agents mcp",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), supportingText = { Text("https://…/mcp  ·  linux:rea mcp") })
            OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { if (hasToken) Text("Saved — leave blank to keep") }, visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Enabled", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            Text("Available in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(projectId == null, { projectId = null }, label = { Text("All projects") })
                projects.forEach { p -> androidx.compose.material3.FilterChip(projectId == p.id, { projectId = p.id }, label = { Text(p.name) }) }
            }
            OutlinedButton(enabled = !busy && (url.startsWith("http") || url.startsWith("linux:")), onClick = {
                busy = true; status = null
                scope.launch {
                    runCatching { val cl = McpClient(url.trim(), tokenNow()); cl.listTools().also { if (name.isBlank()) name = cl.serverName ?: "" } }
                        .onSuccess { list -> tools = list.map { it.name }; status = true to "Connected. ${list.size} tools available." }
                        .onFailure { status = false to (it.message ?: "Connection failed") }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test connection")
            }
            status?.let { (ok, m) -> Text(m, color = if (ok) extra.success else extra.danger) }
            if (tools.isNotEmpty()) {
                Text("Tools", style = MaterialTheme.typography.titleSmall)
                tools.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(enabled = (url.startsWith("http") || url.startsWith("linux:")) && name.isNotBlank(), onClick = {
                scope.launch {
                    c.db.mcp().upsert(McpServerEntity(sid, name.trim(), url.trim(), enabled, tools.joinToString("\n"),
                        if (status?.first == true) "ok" else null, if (created == 0L) System.currentTimeMillis() else created, projectId, pluginId))
                    if (token.isNotBlank()) c.vault.put("mcp:$sid", token.trim())
                    onBack()
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save") }
            Text("MCP tool calls always ask for approval unless you add a rule.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) ConfirmDialog("Remove server?", "The agent will no longer see its tools.", "Remove", true, onConfirm = {
        scope.launch { c.db.mcp().delete(sid); c.vault.remove("mcp:$sid"); onBack() }
    }, onDismiss = { confirmDelete = false })
}
