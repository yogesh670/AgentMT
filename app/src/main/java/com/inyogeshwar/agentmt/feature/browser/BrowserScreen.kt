package com.inyogeshwar.agentmt.feature.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inyogeshwar.agentmt.app.AppContainer
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(c: AppContainer, onBack: () -> Unit, onExtensions: () -> Unit) {
    val b = c.browser
    val ctx = LocalContext.current
    val url by b.url.collectAsState()
    val progress by b.progress.collectAsState()
    val canBack by b.canGoBack.collectAsState()
    val canFwd by b.canGoForward.collectAsState()
    val desktop by b.desktop.collectAsState()
    val agent by b.agentAction.collectAsState()
    val scripts by b.scripts.scripts.collectAsState()
    var address by remember { mutableStateOf(url) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(url) { if (!editing) address = url }
    LaunchedEffect(agent) { if (agent != null) { delay(4000); b.agentAction.value = null } }

    var offer by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); pending = null
    }
    DisposableEffect(Unit) {
        b.fileChooser = { cb, intent -> pending?.onReceiveValue(null); pending = cb
            runCatching { picker.launch(intent) }.onFailure { cb.onReceiveValue(null); pending = null }; true }
        b.onDownload = { u -> if (u.substringBefore('?').endsWith(".user.js")) offer = u else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
        b.onUserScriptLink = { u -> offer = u }
        onDispose { b.fileChooser = null; b.onDownload = null; b.onUserScriptLink = null; b.detach() }
    }
    BackHandler(enabled = canBack) { b.view().goBack() }

    fun go(text: String) { if (text.isNotBlank()) { b.view().loadUrl(BrowserController.normalizeUrl(text)); editing = false; focus.clearFocus() } }

    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "Close browser") }
                OutlinedTextField(address, { address = it; editing = true }, Modifier.weight(1f).heightIn(min = 48.dp), singleLine = true,
                    placeholder = { Text("Search or type a URL") }, textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(address) }), shape = MaterialTheme.shapes.extraLarge)
                IconButton(onClick = { b.view().reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Back") }, { menu = false; b.view().goBack() }, enabled = canBack, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) })
                        DropdownMenuItem({ Text("Forward") }, { menu = false; b.view().goForward() }, enabled = canFwd, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) })
                        DropdownMenuItem({ Text("Extensions (${scripts.count { it.enabled }})") }, { menu = false; onExtensions() })
                        DropdownMenuItem({ Text(if (desktop) "Mobile site" else "Desktop site") }, { menu = false; b.setDesktop(!desktop) })
                        DropdownMenuItem({ Text("Open in another app") }, { menu = false; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, enabled = url.startsWith("http"))
                        DropdownMenuItem({ Text("Sign out of all sites") }, { menu = false; confirmClear = true })
                    }
                }
            }
            if (progress < 100) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            agent?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                        Text("Agent: $it", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            AndroidView(factory = { _ ->
                b.view().also { w -> (w.parent as? android.view.ViewGroup)?.removeView(w); if (b.url.value.isEmpty()) w.loadUrl("https://www.google.com") }
            }, modifier = Modifier.fillMaxSize())
        }
    }

    offer?.let { link -> InstallScriptDialog(c, link, onDone = { offer = null }) }

    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Sign out of all sites?") },
        text = { Text("Clears cookies, logins, site storage and cache for the built-in browser.") },
        confirmButton = { TextButton(onClick = { b.clearData(); confirmClear = false; b.view().reload() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
