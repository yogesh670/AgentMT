package com.inyogeshwar.agentmt.feature.linux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.termux.TermuxBridge
import kotlinx.coroutines.launch

/** Connects AgentMT to the user's own Termux (RUN_COMMAND intent): status, grant, setup command, test, on/off. */
@Composable
fun TermuxCard(c: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(tick) { TermuxBridge.installed(ctx) }
    val permitted = remember(tick) { TermuxBridge.permitted(ctx) }
    var test by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use my Termux", style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        !installed -> "Termux is not installed."
                        !permitted -> "Termux found. Allow AgentMT to run commands in it."
                        settings.useTermux -> "On: commands and termux: MCP servers run in your Termux."
                        else -> "Ready. Turn on to use your Termux packages (node, python, git…)."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.useTermux && installed && permitted, enabled = installed && permitted,
                    onCheckedChange = { on -> scope.launch { c.settings.update { it.copy(useTermux = on) } } })
            }
            if (installed && !permitted) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask.launch(TermuxBridge.PERMISSION) }) { Text("Grant") }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                }) { Text("App settings") }
            }
            if (installed) {
                Text("1. Paste this once in Termux:", style = MaterialTheme.typography.labelLarge)
                Text(TermuxBridge.SETUP, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("termux setup", TermuxBridge.SETUP))
                        runCatching { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(TermuxBridge.PKG)) }
                    }) { Text("Copy & open Termux") }
                    OutlinedButton(enabled = permitted && !busy, onClick = {
                        busy = true; test = "Testing…"
                        scope.launch {
                            test = runCatching {
                                TermuxBridge.run("echo connected; node -v 2>/dev/null || echo 'node: not installed'; python3 -V 2>/dev/null || echo 'python: not installed'; ls -d /storage/emulated/0 >/dev/null 2>&1 && echo 'storage: ok' || echo 'storage: run termux-setup-storage'",
                                    null, 20_000)?.text() ?: "No answer in 20 s. Open Termux once, check allow-external-apps=true, then try again."
                            }.getOrElse { it.message ?: "Failed" }
                            busy = false
                        }
                    }) { Text("Test") }
                }
                Text("2. Projects must be in shared storage (/storage/emulated/0/…) for Termux to see them. MCP: add a server as termux:<command>, e.g. termux:npx -y rea-agents mcp",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Install Termux from F-Droid or its GitHub releases, then come back.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases"))) } }) { Text("Get Termux") }
            }
            test?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}
