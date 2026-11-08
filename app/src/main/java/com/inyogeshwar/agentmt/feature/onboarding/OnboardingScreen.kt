package com.inyogeshwar.agentmt.feature.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.ProviderPresets
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.Logo
import com.inyogeshwar.agentmt.feature.backup.ImportDialog
import com.inyogeshwar.agentmt.feature.projects.ProjectPickerSheet
import com.inyogeshwar.agentmt.feature.providers.ProviderOps
import kotlinx.coroutines.launch

private const val STEPS = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit, onAddProvider: (String) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }
    fun finish() { c.settings.update { it.copy(onboarded = true) }; onDone() }
    BackHandler(step > 0) { step-- }
    val progress by animateFloatAsState((step + 1f) / STEPS, label = "progress")

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(28.dp); Spacer(Modifier.width(10.dp))
            Text("AgentMT", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (step in 1 until STEPS - 1) TextButton(onClick = { step++ }) { Text("Skip") }
        }
        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        AnimatedContent(step, transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 * dir } + fadeOut())
        }, modifier = Modifier.weight(1f), label = "onboarding") { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
                when (s) {
                    0 -> Welcome(onStart = { step = 1 }, onRestore = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) })
                    1 -> WorkspaceStep(c) { step = 2 }
                    2 -> ModelStep(c, onAdvanced = onAddProvider) { step = 3 }
                    3 -> PermissionsStep(c) { step = 4 }
                    else -> TourStep { finish() }
                }
            }
        }
    }
    importUri?.let { ImportDialog(c, it, onDone = { importUri = null; finish() }, onDismiss = { importUri = null }) }
}

@Composable
private fun Welcome(onStart: () -> Unit, onRestore: () -> Unit) {
    Text("A coding agent that\nlives on your phone.", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text("Give it a task. It reads your project, edits files, runs commands and calls MCP tools — and asks before it changes anything.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    AgentDemo()
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Get started") }
    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Restore a backup from another phone")
    }
}

@Composable
private fun WorkspaceStep(c: AppContainer, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    val projectsFlow = remember { c.db.projects().observeAll() }
    val projects by projectsFlow.collectAsStateCompat()
    Text("Where should it work?", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text("The agent only touches files inside the project folder you choose. You can add more later.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    Choice(Icons.Outlined.Science, "Start in a playground", "A scratch folder inside AgentMT. Nothing to set up.", chosen == "playground") {
        scope.launch { c.defaultProject(); chosen = "playground" }
    }
    Choice(Icons.Outlined.CreateNewFolder, "Create or open a project", "A new folder, or one already on your phone.", chosen == "picked") { picker = true }
    chosen?.let { Text(if (it == "playground") "Playground ready." else "Project ready: ${projects.firstOrNull()?.name ?: ""}",
        color = LocalExtra.current.success, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(24.dp))
    Button(onClick = { scope.launch { if (chosen == null) c.defaultProject(); next() } }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Continue") }
    if (picker) ProjectPickerSheet(c, projects, null, onPick = { chosen = "picked" }) { picker = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelStep(c: AppContainer, onAdvanced: (String) -> Unit, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val extra = LocalExtra.current
    var presetId by remember { mutableStateOf("openrouter") }
    var key by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var connectedId by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var local by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val preset = ProviderPresets.byId(presetId)!!
    LaunchedEffect(presetId) {
        baseUrl = preset.baseUrl; error = null
        if (presetId == "ollama") { local = null; local = ProviderOps.detectLocal(); local?.firstOrNull()?.let { baseUrl = it.second } }
    }

    Text("Connect a model", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text("Bring your own key. It's encrypted with Android Keystore and only sent to that provider. You can add more keys and fallbacks later.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderPresets.featured.filter { it.id != "custom" }.forEach { p ->
            FilterChip(presetId == p.id, { presetId = p.id; models = emptyList(); connectedId = null }, label = { Text(if (p.id == "ollama") "Local" else p.name) })
        }
    }
    if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(12.dp))
    if (presetId == "ollama") {
        when (val l = local) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Looking for Ollama / LM Studio on this phone…") }
            else -> Text(if (l.isEmpty()) "No local server found. Start Ollama (e.g. in Termux) or LM Studio on your network and enter its address." else "Found ${l.joinToString { it.first }}.",
                color = if (l.isEmpty()) extra.warning else extra.success, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    } else {
        OutlinedTextField(key, { key = it }, label = { Text("${preset.name} API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        preset.keyUrl?.let { url -> TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Get a key") } }
    }
    error?.let { Text(it, color = extra.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
    if (connectedId == null) {
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && (key.isNotBlank() || !preset.needsKey), onClick = {
            busy = true; error = null
            scope.launch {
                runCatching {
                    val p = ProviderOps.fromPreset(preset).copy(baseUrl = baseUrl.trim().trimEnd('/'))
                    c.db.providers().upsert(p)
                    if (key.isNotBlank()) ProviderOps.addKey(c, p.id, "Main", key)
                    val list = ProviderOps.fetchModels(c, p, key.ifBlank { null })
                    val ids = list.map { it.id }.ifEmpty { preset.suggestedModels }
                    c.db.providers().get(p.id)?.let { c.db.providers().upsert(it.copy(models = ids.joinToString("\n"), defaultModel = ids.firstOrNull { m -> m in preset.suggestedModels } ?: ids.firstOrNull())) }
                    p.id to ids
                }.onSuccess { (id, ids) ->
                    connectedId = id; models = ids
                    picked = ids.firstOrNull { it in preset.suggestedModels } ?: ids.firstOrNull()
                }.onFailure { error = it.message ?: "Couldn't connect" }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
        }
        TextButton(onClick = { onAdvanced("custom") }, modifier = Modifier.fillMaxWidth()) { Text("Custom endpoint or advanced setup") }
    } else {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = extra.success, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Connected — ${models.size} models", color = extra.success)
        }
        Spacer(Modifier.height(8.dp))
        if (models.size > 8) OutlinedTextField(q, { q = it }, placeholder = { Text("Search models (try “free”, “coder”)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Column(Modifier.padding(top = 6.dp)) {
            models.filter { q.isBlank() || it.contains(q, true) }.take(40).forEach { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = m }
                    .background(if (picked == m) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(m, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (picked == m) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(enabled = picked != null, onClick = {
            scope.launch {
                val id = connectedId!!
                c.db.providers().get(id)?.let { c.db.providers().upsert(it.copy(defaultModel = picked)) }
                c.settings.update { it.copy(defaultProviderId = id, defaultModelId = picked) }
                next()
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Use this model") }
    }
}

@Composable
private fun TourStep(done: () -> Unit) {
    Text("Good to know", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Tip(Icons.Outlined.Checklist, "Tasks", "Start a task with a goal, tags and instructions. The agent keeps a live checklist while it works.")
    Tip(Icons.Outlined.Shield, "You approve changes", "Edits show a diff and commands show the exact line before they run. Set rules to stop asking.")
    Tip(Icons.Outlined.SwapHoriz, "Fallback routing", "Add several keys or models. If one is rate-limited, AgentMT moves to the next one you set.")
    Tip(Icons.Outlined.Backup, "Take it with you", "Settings → Backup exports your whole workspace to a single file for your next phone.")
    Tip(Icons.Outlined.FolderOpen, "Your files, your phone", "Projects live on this device. Nothing is uploaded except what you send to your model provider.")
    Spacer(Modifier.height(24.dp))
    Button(onClick = done, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Start building") }
}

@Composable
private fun Choice(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 9.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.collectAsStateCompat() = androidx.compose.runtime.produceState(emptyList<T>(), this) { collect { value = it } }
