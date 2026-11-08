package com.inyogeshwar.agentmt.feature.providers

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.ProviderPresets
import com.inyogeshwar.agentmt.agent.modelList
import com.inyogeshwar.agentmt.agent.routing.ModelMeta
import com.inyogeshwar.agentmt.agent.routing.encodeMeta
import com.inyogeshwar.agentmt.agent.routing.meta
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.data.db.CredentialEntity
import com.inyogeshwar.agentmt.data.db.ProviderEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(c: AppContainer, id: String?, presetId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    val preset = presetId?.let { ProviderPresets.byId(it) }
    var draft by remember { mutableStateOf(if (id == null && preset != null) ProviderOps.fromPreset(preset).copy(name = if (preset.id == "custom") "" else preset.name) else null) }
    LaunchedEffect(id) { if (id != null) draft = c.db.providers().get(id) }
    val p = draft ?: return
    val saved = id != null
    val creds by remember(p.id) { c.db.credentials().observeFor(p.id) }.collectAsState(initial = emptyList())
    var newKeyLabel by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var addingKey by remember { mutableStateOf(!saved && (preset?.needsKey != false)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var manual by remember { mutableStateOf("") }
    var aliasFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val models = p.modelList()
    val meta = p.meta()

    suspend fun persist(x: ProviderEntity) { draft = x; if (saved || c.db.providers().get(x.id) != null) c.db.providers().upsert(x) }

    AppScaffold(if (!saved) "Add provider" else p.name, onBack, actions = {
        if (saved) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(p.name, { draft = p.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(p.kind == "openai", { draft = p.copy(kind = "openai") }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("OpenAI-compatible") }
                SegmentedButton(p.kind == "anthropic", { draft = p.copy(kind = "anthropic") }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Anthropic") }
            }
            OutlinedTextField(p.baseUrl, { draft = p.copy(baseUrl = it) }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), supportingText = { Text("Any OpenAI-compatible gateway works too (OmniRoute, 9Router, LiteLLM…)") })

            // ---- Keys
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API keys", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { addingKey = true }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text("Add key") }
            }
            if (creds.size > 1) Text("Keys are tried top to bottom. When one is rate-limited, AgentMT cools it down and uses the next. Only add keys you're allowed to use — splitting work across accounts to dodge a provider's free limits can break its terms.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            creds.forEachIndexed { i, k -> KeyRow(c, p, k, i, creds) }
            if (creds.isEmpty() && !addingKey) Text(if (preset?.needsKey == false) "No key needed for this provider." else "No key yet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (addingKey) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newKeyLabel, { newKeyLabel = it }, label = { Text("Label (e.g. Personal, Work, Account 2)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newKey, { newKey = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), supportingText = { Text("Encrypted with Android Keystore. Never shown again.") })
                Row {
                    (preset ?: ProviderPresets.byId(p.id))?.keyUrl?.let { url -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Get a key") } }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { addingKey = false; newKey = "" }) { Text("Cancel") }
                    Button(enabled = newKey.isNotBlank(), onClick = {
                        scope.launch {
                            if (c.db.providers().get(p.id) == null) c.db.providers().upsert(p)
                            ProviderOps.addKey(c, p.id, newKeyLabel.ifBlank { if (creds.isEmpty()) "Main" else "" }, newKey)
                            newKey = ""; newKeyLabel = ""; addingKey = false
                        }
                    }) { Text("Save key") }
                }
            }

            // ---- Models
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Models", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !busy && p.baseUrl.length > 10, onClick = {
                    busy = true; status = null
                    scope.launch {
                        if (c.db.providers().get(p.id) == null) c.db.providers().upsert(p)
                        runCatching { ProviderOps.fetchModels(c, p, null) }
                            .onSuccess { list ->
                                val fresh = c.db.providers().get(p.id)!!
                                val ids = (list.map { it.id } + fresh.modelList().filter { m -> list.none { it.id == m } }).distinct()
                                persist(fresh.copy(models = ids.joinToString("\n"), defaultModel = fresh.defaultModel?.takeIf { it in ids } ?: ids.firstOrNull()))
                                status = true to "Connected. ${list.size} models found."
                            }
                            .onFailure { status = false to (it.message ?: "Could not connect") }
                        busy = false
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Fetch models") }
            }
            status?.let { (ok, msg) -> Text(msg, color = if (ok) extra.success else extra.danger, style = MaterialTheme.typography.bodyMedium) }
            Text("Tap a model to make it the default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (showAll) models else models.take(60)).forEach { m ->
                    val mm = meta[m]
                    FilterChip(m == p.defaultModel, { scope.launch { persist(p.copy(defaultModel = m)) } },
                        label = { Text((mm?.alias ?: m) + if (mm?.vision == true) "  ◐" else "", fontFamily = if (mm?.alias == null) Mono else null, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(vertical = 0.dp).let { mod -> mod })
                }
            }
            if (models.size > 60 && !showAll) TextButton(onClick = { showAll = true }) { Text("Show all ${models.size}") }
            if (models.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alias for default model", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = p.defaultModel != null, onClick = { aliasFor = p.defaultModel }) { Text(meta[p.defaultModel]?.alias ?: "Set alias") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manual, { manual = it }, label = { Text("Add model ID") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = manual.isNotBlank(), onClick = {
                    scope.launch { persist(p.copy(models = (models + manual.trim()).distinct().joinToString("\n"), defaultModel = p.defaultModel ?: manual.trim())); manual = "" }
                }) { Text("Add") }
            }
            Text("◐ = accepts images (when the provider says so). Unknown capabilities are never guessed.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f)); Switch(p.enabled, { v -> scope.launch { persist(p.copy(enabled = v)) } })
            }
            Button(enabled = p.baseUrl.startsWith("http") && models.isNotEmpty(), onClick = {
                scope.launch {
                    val x = p.copy(name = p.name.ifBlank { p.baseUrl.removePrefix("https://").substringBefore('/') }, baseUrl = p.baseUrl.trim().trimEnd('/'))
                    c.db.providers().upsert(x)
                    val s = c.settings.state.value
                    if (s.defaultProviderId == null || c.db.providers().get(s.defaultProviderId) == null || s.defaultProviderId == x.id)
                        c.settings.update { it.copy(defaultProviderId = x.id, defaultModelId = x.defaultModel) }
                    onBack()
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save") }
            if (models.isEmpty()) Text("Add a key and tap Fetch models (or type a model ID) to save.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    aliasFor?.let { m ->
        var a by remember(m) { mutableStateOf(meta[m]?.alias ?: "") }
        AlertDialog(onDismissRequest = { aliasFor = null }, title = { Text("Alias") },
            text = { Column { Text(m, fontFamily = Mono, style = MaterialTheme.typography.bodySmall); OutlinedTextField(a, { a = it }, singleLine = true, placeholder = { Text("e.g. Fast coder") }) } },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    val mm = meta.toMutableMap(); mm[m] = (mm[m] ?: ModelMeta()).copy(alias = a.trim().ifBlank { null })
                    persist(p.copy(modelMeta = encodeMeta(mm))); aliasFor = null
                }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { aliasFor = null }) { Text("Cancel") } })
    }
    if (confirmDelete) ConfirmDialog("Remove provider?", "All its keys will be deleted from this phone.", "Remove", true, onConfirm = {
        scope.launch { ProviderOps.deleteProvider(c, p.id); onBack() }
    }, onDismiss = { confirmDelete = false })
}

@Composable
private fun KeyRow(c: AppContainer, p: ProviderEntity, k: CredentialEntity, index: Int, all: List<CredentialEntity>) {
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    var testing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val cooling = k.cooldownUntil > now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Key, null, Modifier.size(18.dp), tint = if (k.enabled) MaterialTheme.colorScheme.primary else extra.subtle)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(6.dp))
                Text(c.vault.hint("cred:${k.id}") ?: "missing", fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = extra.subtle)
            }
            Text(
                when {
                    !k.enabled -> "Off"
                    k.status == "invalid" -> "Key rejected — ${k.lastError ?: "check it"}"
                    cooling -> "Cooling down ${((k.cooldownUntil - now) / 60_000).coerceAtLeast(1)} min · ${k.lastError ?: ""}"
                    else -> "${k.requests} requests · ${(k.inputTokens + k.outputTokens) / 1000}k tokens" + if (k.failures > 0) " · ${k.failures} failed" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (k.status == "invalid") extra.danger else if (cooling) extra.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text("Test", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                testing = true; scope.launch { ProviderOps.testKey(c, p, k); testing = false }
            }.padding(8.dp))
        if (cooling || k.status == "invalid") Text("Reset", style = MaterialTheme.typography.labelLarge, color = extra.warning,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { c.router.resetCredential(k.id) } }.padding(8.dp))
        Column {
            IconButton(enabled = index > 0, onClick = { scope.launch { swap(c, all, index, index - 1) } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(enabled = index < all.size - 1, onClick = { scope.launch { swap(c, all, index, index + 1) } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
        }
        Switch(k.enabled, { v -> scope.launch { c.db.credentials().upsert(k.copy(enabled = v)) } }, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Delete key", tint = extra.subtle) }
    }
    if (confirm) ConfirmDialog("Delete ${k.label}?", "The key is removed from this phone.", "Delete", true,
        onConfirm = { scope.launch { ProviderOps.deleteKey(c, k) } }, onDismiss = { confirm = false })
}

private suspend fun swap(c: AppContainer, all: List<CredentialEntity>, a: Int, b: Int) {
    val list = all.toMutableList(); val t = list[a]; list[a] = list[b]; list[b] = t
    list.forEachIndexed { i, k -> if (k.priority != i) c.db.credentials().upsert(k.copy(priority = i)) }
}
