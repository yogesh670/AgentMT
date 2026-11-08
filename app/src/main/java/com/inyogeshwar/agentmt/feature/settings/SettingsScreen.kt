package com.inyogeshwar.agentmt.feature.settings

import androidx.compose.material.icons.automirrored.outlined.*
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.inyogeshwar.agentmt.BuildConfigShim
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import com.inyogeshwar.agentmt.data.db.clearAllTablesSafe
import com.inyogeshwar.agentmt.data.prefs.ApprovalMode
import com.inyogeshwar.agentmt.data.prefs.ThemeMode
import com.inyogeshwar.agentmt.feature.projects.allFilesAccessIntent
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    c: AppContainer, onBack: () -> Unit, onProviders: () -> Unit, onRules: () -> Unit, onTools: () -> Unit,
    onRouting: () -> Unit = {}, onBackup: () -> Unit = {},
    onLinux: () -> Unit = {}, onBrowser: () -> Unit = {}, onExtensions: () -> Unit = {}, onLicenses: () -> Unit = {},
) {
    val s by c.settings.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val extra = LocalExtra.current
    var access by remember { mutableStateOf(c.hasAllFilesAccess()) }
    var confirmWipe by remember { mutableStateOf(false) }
    var tokenDialog by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf(s.customInstructions) }
    var steps by remember { mutableIntStateOf(s.maxSteps) }
    val lifecycle = LocalLifecycleOwner.current
    val power = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
    fun unrestricted() = runCatching { power.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    var battery by remember { mutableStateOf(unrestricted()) }
    val voice = remember { com.inyogeshwar.agentmt.core.DoneVoice(ctx) }
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { access = c.hasAllFilesAccess(); battery = unrestricted() } }

    AppScaffold("Settings", onBack) { pad ->
        LazyColumn(contentPadding = pad) {
            item { SectionLabel("Models") }
            item {
                Card {
                    ListRow("Providers & API keys", s.defaultModelId?.let { "Default: $it" } ?: "No model yet", Icons.Outlined.Key, iconTint = MaterialTheme.colorScheme.primary, onClick = onProviders)
                    ListRow("Routing & fallback", "Profiles, multiple keys, usage, health", Icons.Outlined.SwapHoriz, iconTint = MaterialTheme.colorScheme.secondary, onClick = onRouting)
                }
            }

            item { SectionLabel("Agent") }
            item {
                Card {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        ApprovalMode.entries.forEach { m ->
                            Row(Modifier.fillMaxWidth().selectable(s.approvalMode == m) { c.settings.update { it.copy(approvalMode = m) } }
                                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(s.approvalMode == m, null)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(16.dp)) {
                            Text("Reasoning effort (default)", style = MaterialTheme.typography.bodyLarge)
                            val efforts = listOf("auto", "low", "medium", "high")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                efforts.forEachIndexed { i, e ->
                                    SegmentedButton(s.effort == e, { c.settings.update { it.copy(effort = e) } }, SegmentedButtonDefaults.itemShape(i, efforts.size)) { Text(e.replaceFirstChar { it.uppercase() }) }
                                }
                            }
                            Text("Sent to OpenAI-compatible reasoning models (GPT-5, o-series, DeepSeek, Gemini thinking). If a model rejects it, use Auto.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow("Permission rules", "Fine-grained allow / ask / deny", Icons.AutoMirrored.Outlined.Rule, onClick = onRules)
                        ListRow("Tools, MCP & skills", null, Icons.Outlined.Extension, onClick = onTools)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(16.dp)) {
                            Text("Max steps per task: $steps", style = MaterialTheme.typography.bodyLarge)
                            Slider(steps.toFloat(), { steps = it.toInt() }, valueRange = 5f..60f, steps = 10,
                                onValueChangeFinished = { c.settings.update { it.copy(maxSteps = steps) } })
                            OutlinedTextField(instructions, { instructions = it }, label = { Text("Custom instructions") },
                                placeholder = { Text("e.g. Reply in Hinglish. Use Kotlin and Compose.") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            if (instructions != s.customInstructions) TextButton(onClick = { c.settings.update { it.copy(customInstructions = instructions.trim()) } },
                                modifier = Modifier.align(Alignment.End)) { Text("Save instructions") }
                        }
                    }
                }
            }

            item { SectionLabel("Linux & browser") }
            item {
                Card {
                    if (!com.inyogeshwar.agentmt.BuildConfig.FULL) ListRow("Linux, build & tools", "Not in the Google Play version. Get the full version from GitHub releases.", Icons.Outlined.Memory)
                    else ListRow("Linux, build & tools", com.inyogeshwar.agentmt.core.linux.LinuxEnv.distro?.let { "${it.label} ${it.version} active" + if (com.inyogeshwar.agentmt.core.linux.ApkBuildKit.ready()) " · APK build ready" else "" }
                        ?: "Ubuntu or Alpine, APK builds, GitHub/apt/pip tools", Icons.Outlined.Memory, iconTint = MaterialTheme.colorScheme.primary, onClick = onLinux)
                    ListRow("Browser", "Built-in browser the agent can use; sign in to sites here", Icons.Outlined.Language, onClick = onBrowser)
                    ListRow("Browser extensions", "${c.browser.scripts.scripts.collectAsState().value.size} installed · userscripts", Icons.Outlined.Extension, onClick = onExtensions)
                }
            }

            item { SectionLabel("Background & voice") }
            item {
                Card {
                    ListRow("Run in background (battery)", if (battery) "Unrestricted — the agent keeps working with the screen off" else "Restricted — tap and choose Allow / Unrestricted",
                        Icons.Outlined.BatteryChargingFull, trailing = { Pill(if (battery) "Allowed" else "Off", if (battery) extra.success else extra.subtle) },
                        onClick = { runCatching { if (!com.inyogeshwar.agentmt.BuildConfig.FULL) error("play"); ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + ctx.packageName))) }.onFailure {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
                    ListRow("Auto-start / background activity", "On Xiaomi, Oppo, Vivo, Realme and Samsung also enable Auto-start and turn off \"sleeping apps\" for AgentMT.",
                        Icons.Outlined.PowerSettingsNew, onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + ctx.packageName))) } })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Voice message when a task is done", style = MaterialTheme.typography.bodyLarge)
                            Text("Says \"Your task is completed\" when the agent finishes, even with the phone locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { voice.speak() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Test voice") }
                        }
                        Switch(s.voiceDone, { v -> c.settings.update { it.copy(voiceDone = v) } })
                    }
                }
            }

            item { SectionLabel("Storage") }
            item {
                Card {
                    ListRow("All files access", if (access) "Granted — you can open any folder on internal storage" else "Off — only AgentMT projects can be used",
                        Icons.Outlined.SdStorage, trailing = { Pill(if (access) "On" else "Off", if (access) extra.success else extra.subtle) },
                        onClick = { ctx.startActivity(allFilesAccessIntent(ctx.packageName)) })
                    ListRow("AgentMT projects folder", c.privateProjectsDir.path.replace("/storage/emulated/0", "Internal storage"), null)
                }
            }

            item { SectionLabel("Appearance") }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(s.theme == m, { c.settings.update { it.copy(theme = m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.name) }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 31) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Wallpaper colors", style = MaterialTheme.typography.bodyLarge)
                                Text("Use Material You dynamic color", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(s.dynamicColor, { v -> c.settings.update { it.copy(dynamicColor = v) } })
                        }
                    }
                }
            }

            item { SectionLabel("Data") }
            item { Card {
                ListRow("Backup & restore", "Move your workspace to another phone", Icons.Outlined.Backup, iconTint = MaterialTheme.colorScheme.primary, onClick = onBackup)
                ListRow("GitHub token", if (c.vault.has("github")) "Saved ${c.vault.hint("github")} — used for skill/plugin installs" else "Optional. Raises GitHub's 60 requests/hour limit for installs.",
                    Icons.Outlined.Code, onClick = { tokenDialog = true })
            } }
            item { Card { ListRow("Delete all chats", "Removes conversations and run history. Project files are kept.", Icons.Outlined.DeleteSweep,
                iconTint = MaterialTheme.colorScheme.error, onClick = { confirmWipe = true }) } }

            item { SectionLabel("About") }
            item { Card { ListRow("AgentMT ${BuildConfigShim.versionName(ctx)}", "Kotlin · Jetpack Compose · Material 3 · Room · OkHttp", Icons.Outlined.Info)
                ListRow("Open source licenses", "proot, talloc, Termux terminal, AndroidX, OkHttp…", Icons.Outlined.Info, onClick = onLicenses) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    if (tokenDialog) {
        var t by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { tokenDialog = false }, title = { Text("GitHub token") },
            text = { Column {
                Text("A fine-grained token with public read access is enough. Stored encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(t, { t = it }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = { if (t.isNotBlank()) c.vault.put("github", t.trim()); tokenDialog = false }) { Text("Save") } },
            dismissButton = { Row {
                if (c.vault.has("github")) TextButton(onClick = { c.vault.remove("github"); tokenDialog = false }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { tokenDialog = false }) { Text("Cancel") }
            } })
    }
    if (confirmWipe) ConfirmDialog("Delete all chats?", "Every conversation and run will be removed from this phone. This can't be undone.", "Delete all", true,
        onConfirm = { scope.launch { c.db.clearAllTablesSafe() } }, onDismiss = { confirmWipe = false })
}
