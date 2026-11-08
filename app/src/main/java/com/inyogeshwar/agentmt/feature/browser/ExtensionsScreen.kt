package com.inyogeshwar.agentmt.feature.browser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Fetches a userscript over HTTPS. */
suspend fun fetchUserScript(url: String): String = withContext(Dispatchers.IO) {
    require(url.startsWith("https://") || url.startsWith("http://")) { "Use an http(s) link to a .user.js file" }
    Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
        val body = r.body!!.string()
        require(body.length < 2_000_000) { "Script is too large" }
        body
    }
}

@Composable
fun ExtensionsScreen(c: AppContainer, onBack: () -> Unit, onBrowse: (String) -> Unit) {
    val store = c.browser.scripts
    val scripts by store.scripts.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val code = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                "Installed " + store.install(code, "file").name
            }.getOrElse { it.message ?: "Couldn't install" }
        }
    }

    AppScaffold("Browser extensions", onBack, floating = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Extension, null) }, text = { Text("Add extension") })
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                Text("Extensions run as userscripts (Tampermonkey format) on the pages they match, in your browser and while the agent browses. " +
                    "Chrome Web Store extensions can't run inside Android's WebView; most of them have a userscript version on Greasy Fork. " +
                    "Tap Install on greasyfork.org inside the Browser and AgentMT picks it up.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onBrowse("https://greasyfork.org") }, label = { Text("Browse Greasy Fork") })
                    AssistChip(onClick = { pickFile.launch(arrayOf("*/*")) }, label = { Text("Import .user.js file") })
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp, 8.dp)) }
            }
            if (scripts.isEmpty()) item { EmptyState(Icons.Outlined.Extension, "No extensions yet", "Add one from a link, a file or Greasy Fork.", Modifier.height(320.dp)) }
            else {
                item { SectionLabel("Installed (${scripts.size})") }
                items(scripts, key = { it.id }) { s ->
                    Card(Modifier.padding(bottom = 8.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name + if (s.version.isNotBlank()) "  v${s.version}" else "", style = MaterialTheme.typography.titleSmall)
                                if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text("Runs on: " + (s.matches + s.includes).take(3).joinToString().ifEmpty { "no sites" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Switch(s.enabled, { store.setEnabled(s.id, it) })
                            IconButton(onClick = { store.delete(s.id) }) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (adding) {
        var link by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var tab by remember { mutableIntStateOf(0) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add extension") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Link") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Paste code") })
                }
                if (tab == 0) OutlinedTextField(link, { link = it }, label = { Text(".user.js link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else OutlinedTextField(code, { code = it }, label = { Text("// ==UserScript== …") }, minLines = 6, maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(onClick = {
                adding = false
                if (tab == 0) installUrl = link.trim()
                else message = runCatching { "Installed " + store.install(code, "pasted").name }.getOrElse { it.message ?: "Couldn't install" }
            }, enabled = if (tab == 0) link.isNotBlank() else code.isNotBlank()) { Text("Continue") }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
    installUrl?.let { InstallScriptDialog(c, it, onDone = { r -> installUrl = null; if (r != null) message = r }) }
}

/** Downloads a userscript, shows what it is, and installs it on confirmation. */
@Composable
fun InstallScriptDialog(c: AppContainer, url: String, onDone: (String?) -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) { runCatching { fetchUserScript(url) }.onSuccess { code = it }.onFailure { error = it.message ?: "Download failed" } }
    val meta = code?.let { UserScriptMeta.parse(it) }
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text(meta?.name ?: "Install extension") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                code == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Downloading…") }
                meta == null -> Text("That file isn't a userscript.", color = MaterialTheme.colorScheme.error)
                else -> {
                    if (meta.description.isNotBlank()) Text(meta.description, style = MaterialTheme.typography.bodyMedium)
                    Text("Runs on: " + (meta.matches + meta.includes).take(6).joinToString().ifEmpty { "no sites" }, style = MaterialTheme.typography.bodySmall)
                    Text("Only install scripts you trust: they can read and change the pages they run on, including sites you're signed in to.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = meta != null, onClick = { onDone(runCatching { "Installed " + c.browser.scripts.install(code!!, url).name }.getOrElse { it.message }) }) { Text("Install") }
    }, dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } })
}
