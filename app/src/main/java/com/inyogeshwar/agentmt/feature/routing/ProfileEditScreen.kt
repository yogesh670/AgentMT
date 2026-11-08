package com.inyogeshwar.agentmt.feature.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.modelList
import com.inyogeshwar.agentmt.agent.routing.ProfileCandidate
import com.inyogeshwar.agentmt.agent.routing.encodeCandidates
import com.inyogeshwar.agentmt.agent.routing.parseCandidates
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.data.db.RoutingProfileEntity
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(c: AppContainer, id: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val providers by remember { c.db.providers().observeAll() }.collectAsState(initial = emptyList())
    val creds by remember { c.db.credentials().observeAll() }.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ordered") }
    var cands by remember { mutableStateOf(listOf<ProfileCandidate>()) }
    var created by remember { mutableStateOf(System.currentTimeMillis()) }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        id?.let { c.db.routing().get(it) }?.let { p -> name = p.name; mode = p.mode; cands = parseCandidates(p.candidatesJson); created = p.createdAt }
    }
    val pName = providers.associate { it.id to it.name }
    val cName = creds.associate { it.id to it.label }

    AppScaffold(if (id == null) "New profile" else name.ifBlank { "Profile" }, onBack, actions = {
        if (id != null) IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Free tier chain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(mode == "ordered", { mode = "ordered" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Fall back in order") }
                SegmentedButton(mode == "pinned", { mode = "pinned" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Pinned (first only)") }
            }
            Text(if (mode == "ordered") "Tried top to bottom. A step that fails before any output moves to the next line." else "Only the first line is used; other keys of that provider are still tried if key rotation is on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cands.forEachIndexed { i, cd ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cd.model, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pName[cd.providerId] ?: "Missing provider"} · ${cd.credentialId?.let { cName[it] ?: "missing key" } ?: "any key"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = i > 0, onClick = { cands = cands.toMutableList().also { l -> val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t } }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                    IconButton(enabled = i < cands.size - 1, onClick = { cands = cands.toMutableList().also { l -> val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t } }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                    IconButton(onClick = { cands = cands.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
            OutlinedButton(onClick = { adding = true }, enabled = providers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(if (providers.isEmpty()) "Add a provider first" else "Add candidate")
            }
            Button(enabled = name.isNotBlank() && cands.isNotEmpty(), onClick = {
                scope.launch {
                    c.db.routing().upsert(RoutingProfileEntity(id ?: UUID.randomUUID().toString(), name.trim(), mode, encodeCandidates(cands), created))
                    onBack()
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save profile") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (adding) {
        var pid by remember { mutableStateOf(providers.firstOrNull()?.id) }
        var model by remember { mutableStateOf<String?>(null) }
        var cred by remember { mutableStateOf<String?>(null) }
        var q by remember { mutableStateOf("") }
        val p = providers.firstOrNull { it.id == pid }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add candidate") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { providers.forEach { pr -> FilterChip(pid == pr.id, { pid = pr.id; model = null; cred = null }, label = { Text(pr.name) }) } }
                Text("Key", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(cred == null, { cred = null }, label = { Text("Any (in order)") })
                    creds.filter { it.providerId == pid }.forEach { k -> FilterChip(cred == k.id, { cred = k.id }, label = { Text(k.label) }) }
                }
                Text("Model", style = MaterialTheme.typography.labelLarge)
                val ms = p?.modelList().orEmpty()
                if (ms.size > 10) OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search") })
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    ms.filter { q.isBlank() || it.contains(q, true) }.take(80).forEach { m ->
                        Text(m, fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                            color = if (model == m) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (model == m) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable { model = m }.padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = pid != null && model != null, onClick = { cands = cands + ProfileCandidate(pid!!, model!!, cred); adding = false }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
    if (confirm) ConfirmDialog("Delete profile?", "Chats using it will go back to the default model.", "Delete", true, onConfirm = {
        scope.launch {
            id?.let { c.db.routing().delete(it) }
            val s = c.settings.state.value
            c.settings.update { it.copy(defaultProfileId = s.defaultProfileId.takeIf { x -> x != id }, fallbackProfileId = s.fallbackProfileId.takeIf { x -> x != id }) }
            onBack()
        }
    }, onDismiss = { confirm = false })
}
