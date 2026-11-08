package com.inyogeshwar.agentmt.feature.linux

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.linux.ApkBuildKit
import com.inyogeshwar.agentmt.core.linux.Distro
import com.inyogeshwar.agentmt.core.linux.InstalledTool
import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import com.inyogeshwar.agentmt.core.linux.LinuxJobs
import com.inyogeshwar.agentmt.core.linux.ToolSpecs
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KINDS = listOf("github" to "GitHub repo", "linux" to "Linux package", "python" to "Python tool", "npm" to "Node CLI")

/** Linux subsystem, on-phone APK builds, and tools installed into Linux, in one place. */
@Composable
fun LinuxScreen(c: AppContainer, onBack: () -> Unit, onTerminal: () -> Unit) {
    val linux by LinuxEnv.state.collectAsState()
    val jobs by c.linuxJobs.jobs.collectAsState()
    val tools by c.toolStore.tools.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val extra = LocalExtra.current
    var pick by remember { mutableStateOf(LinuxEnv.active ?: Distro.Ubuntu) }
    var filter by remember { mutableStateOf("all") }
    var addTool by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<InstalledTool?>(null) }
    var removeDistro by remember { mutableStateOf<Distro?>(null) }
    var importList by remember { mutableStateOf<List<InstalledTool>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var buildReady by remember { mutableStateOf(ApkBuildKit.ready()) }
    LaunchedEffect(jobs, linux) { buildReady = ApkBuildKit.ready() }
    val distro = LinuxEnv.distro
    val ready = linux is LinuxEnv.State.Ready

    fun installTool(t: InstalledTool) {
        val d = distro ?: return
        c.linuxJobs.run("Install ${t.name}", ToolSpecs.installScript(t, d)) { code ->
            if (code == 0) {
                val desc = if (t.kind == "github") ToolSpecs.describeRepo(LinuxEnv.hostPath(t.path)) else ""
                c.toolStore.add(t.copy(description = desc, installedAt = System.currentTimeMillis()))
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            note = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(ToolSpecs.manifest(tools).toByteArray()) } }; "Saved ${tools.size} tools" }
                .getOrElse { "Backup failed: ${it.message}" }
        }
    }
    var distroBusy by remember { mutableStateOf<String?>(null) }
    var resetDistro by remember { mutableStateOf<Distro?>(null) }
    val distroBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val d = LinuxEnv.active ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            distroBusy = "Backing up ${d.label}…"
            note = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { out -> LinuxEnv.backup(d, out) { distroBusy = "Backing up ${d.label}: $it" } } }
                .fold({ "Backed up ${d.label} ($it files)" }, { "Backup failed: ${it.message}" })
            distroBusy = null
        }
    }
    val distroRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            distroBusy = "Restoring…"
            note = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { LinuxEnv.restore(it) { p -> distroBusy = p } } }
                .fold({ "Restored ${it.label}. It is now active." }, { "Restore failed: ${it.message}" })
            distroBusy = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } } }
                .mapCatching { ToolSpecs.parseManifest(it) }
                .onSuccess { importList = it }.onFailure { note = "Import failed: ${it.message}" }
        }
    }

    AppScaffold("Linux, build & tools", onBack) { pad ->
        LazyColumn(contentPadding = pad) {
            // ---------- Linux subsystem ----------
            item { SectionLabel("Termux") }
            item { TermuxCard(c) }
            item { SectionLabel("Linux subsystem") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Inbuilt Linux", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            when (val s = linux) {
                                is LinuxEnv.State.Ready -> Pill("${s.distro.label} · active", extra.success)
                                is LinuxEnv.State.Installing -> Pill("Installing", MaterialTheme.colorScheme.primary)
                                LinuxEnv.State.Unsupported -> Pill("Not supported", extra.subtle)
                                else -> Pill("Not installed", extra.subtle)
                            }
                        }
                        Text("A real Linux userland inside AgentMT (proot, no root). apt or apk, bash, python3, pip, node, npm, git, curl, gcc… The agent runs its commands here.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (val s = linux) {
                            LinuxEnv.State.Unsupported -> Text("Needs a 64-bit ARM (arm64) phone. The Android shell still works.", color = MaterialTheme.colorScheme.error)
                            is LinuxEnv.State.Installing -> {
                                Text(s.step + if (s.speed.isNotEmpty()) " · ${s.speed}" else "", style = MaterialTheme.typography.bodyMedium)
                                if (s.progress != null) LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Keep AgentMT open until this finishes.", style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {
                                Distro.entries.forEach { d ->
                                    val inst = LinuxEnv.installed(d)
                                    Row(Modifier.fillMaxWidth().selectable(pick == d) { pick = d }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(pick == d, null)
                                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                            Text("${d.label} ${d.version}" + if (d == Distro.Ubuntu) "  (recommended)" else "", style = MaterialTheme.typography.bodyLarge)
                                            Text((if (d == Distro.Ubuntu) "apt, glibc: runs most Linux software and the APK build kit" else "apk, tiny and fast")
                                                + " · ${d.downloadMb} MB download" + if (inst) " · installed" else "",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (inst) IconButton(onClick = { removeDistro = d }) { Icon(Icons.Outlined.Delete, "Remove ${d.label}", tint = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                if (s is LinuxEnv.State.Failed) Text("Install failed: ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when {
                                        !LinuxEnv.installed(pick) -> Button(onClick = { scope.launch { LinuxEnv.install(pick) } }) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Install ${pick.label}") }
                                        LinuxEnv.active != pick -> Button(onClick = { LinuxEnv.setActive(pick) }) { Text("Use ${pick.label}") }
                                    }
                                    if (ready) OutlinedButton(onClick = onTerminal) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Open Terminal") }
                                }
                                distroBusy?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val act = LinuxEnv.active
                                    if (act != null) OutlinedButton(enabled = distroBusy == null, onClick = { distroBackup.launch("agentmt-${act.id}-backup.tar.gz") }) { Icon(Icons.Outlined.Archive, null); Spacer(Modifier.width(6.dp)); Text("Back up") }
                                    OutlinedButton(enabled = distroBusy == null, onClick = { distroRestore.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*")) }) { Icon(Icons.Outlined.Unarchive, null); Spacer(Modifier.width(6.dp)); Text("Restore") }
                                    if (act != null) OutlinedButton(enabled = distroBusy == null, onClick = { resetDistro = act }) { Icon(Icons.Outlined.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset") }
                                }
                                if (ready) Text("Android commands inside Linux: agentmt-open <url|file>, agentmt-share, agentmt-clipboard-get / -set, agentmt-notify, agentmt-toast, agentmt-install <file.apk>.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // ---------- APK build ----------
            item { SectionLabel("APK build") }
            item {
                val running = jobs.any { it.running && it.title == ApkBuildKit.TITLE }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Build APKs on this phone", style = MaterialTheme.typography.titleMedium)
                                Text("Kotlin, Jetpack Compose and Java apps. The agent writes the app, builds it with Gradle, fixes build errors and gives you the APK.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            buildReady -> {
                                Pill("Ready", extra.success)
                                Text(ApkBuildKit.versions(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { c.linuxJobs.run("Check build tools", ApkBuildKit.checkScript) }, enabled = !running) { Text("Re-check") }
                                    OutlinedButton(onClick = { c.linuxJobs.run(ApkBuildKit.TITLE, ApkBuildKit.installScript) }, enabled = !running) { Text("Reinstall") }
                                }
                            }
                            !ready -> Text("Install Linux first (Ubuntu recommended).", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                Text("Installs OpenJDK 17, Gradle 8.9, Android SDK 35 and arm64 aapt2 into ${distro?.label}. About 600 MB download and 1.5 GB of space; takes 10–30 minutes.",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { c.linuxJobs.run(ApkBuildKit.TITLE, ApkBuildKit.installScript) }, enabled = !running) {
                                    Text(if (running) "Setting up…" else "Install build tools")
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Tools ----------
            item {
                Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Installed tools & skills (${tools.size})", Modifier.weight(1f))
                    FilledTonalButton(onClick = { addTool = true }, enabled = ready) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("Add tool / skill") }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("all" to "All") + KINDS).forEach { (k, l) ->
                        FilterChip(filter == k, { filter = k }, label = { Text(l + " (" + (if (k == "all") tools.size else tools.count { it.kind == k }) + ")") })
                    }
                }
            }
            if (ready && distro == Distro.Ubuntu) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolSpecs.KITS.forEach { k ->
                        val busy = jobs.any { it.running && it.title == "Kit: ${k.title}" }
                        AssistChip(enabled = !busy, onClick = {
                            c.linuxJobs.run("Kit: ${k.title}", "set -e\nexport DEBIAN_FRONTEND=noninteractive\napt-get update\n" + ToolSpecs.kitScript(k, Distro.Ubuntu)) { code ->
                                if (code == 0 && k.tools.any { it.name == "rea-agents" } && c.db.mcp().all().none { it.url == "linux:rea mcp" })
                                    c.db.mcp().upsert(com.inyogeshwar.agentmt.data.db.McpServerEntity(java.util.UUID.randomUUID().toString(), "REA", "linux:rea mcp",
                                        createdAt = System.currentTimeMillis()))
                                if (code == 0) k.tools.forEach { t ->
                                    val desc = if (t.kind == "github") ToolSpecs.describeRepo(LinuxEnv.hostPath(t.path)) else ""
                                    c.toolStore.add(t.copy(description = desc, installedAt = System.currentTimeMillis()))
                                }
                            }
                            note = "Installing ${k.title} kit: ${k.about}"
                        }, label = { Text(if (busy) "Installing ${k.title}…" else "+ ${k.title} kit") })
                    }
                }
            }
            val shown = tools.filter { filter == "all" || it.kind == filter }
            if (shown.isEmpty()) item {
                Text(if (!ready) "Install Linux to add tools." else "No tools yet. Add a GitHub repo (skills like REA are detected automatically), Linux packages (apt/apk) or Python tools (pip).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp, 8.dp))
            }
            items(shown, key = { it.id }) { t ->
                Card(Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (t.kind) { "github" -> Icons.Outlined.Code; "python" -> Icons.Outlined.DataObject; else -> Icons.Outlined.Inventory2 }, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text(KINDS.first { it.first == t.kind }.second + (if (t.kind == "github") " · ${t.path}" else "") +
                                (if (t.kind == "github" && LinuxEnv.ready && ToolSpecs.skillDirs(LinuxEnv.hostPath(t.path)).isNotEmpty()) " · has skills" else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        IconButton(onClick = { remove = t }) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // ---------- Backup / share / import ----------
            item { SectionLabel("Backup, share & import tools") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Save your tool list to a file or share it. Importing lets you pick exactly which tools to install.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { backupLauncher.launch("agentmt-tools.json") }, enabled = tools.isNotEmpty()) { Text("Backup") }
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                                    .putExtra(Intent.EXTRA_SUBJECT, "AgentMT tools").putExtra(Intent.EXTRA_TEXT, ToolSpecs.manifest(tools)), "Share tools"))
                            }, enabled = tools.isNotEmpty()) { Text("Share") }
                            Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = ready) { Text("Import") }
                        }
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }

            // ---------- Jobs ----------
            if (jobs.isNotEmpty()) {
                item { SectionLabel("Activity") }
                items(jobs.reversed(), key = { "job" + it.id }) { j -> JobCard(j, onDismiss = { c.linuxJobs.dismiss(j.id) }) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (addTool) AddToolDialog(onDismiss = { addTool = false }, onAdd = { t -> addTool = false; installTool(t) })
    remove?.let { t ->
        ConfirmDialog("Remove ${t.name}?", when (t.kind) { "github" -> "Deletes ${t.path} from Linux."; "linux" -> "Uninstalls the package."; else -> "Uninstalls the Python package." },
            "Remove", true, onConfirm = {
                val d = distro
                if (d != null) c.linuxJobs.run("Remove ${t.name}", ToolSpecs.removeScript(t, d))
                c.toolStore.remove(t)
            }, onDismiss = { remove = null })
    }
    resetDistro?.let { d -> ConfirmDialog("Reset ${d.label}?", "Deletes everything inside ${d.label} (installed packages, /root, /opt/tools) and installs a fresh copy. Back up first if you need anything.", "Reset", true,
        onConfirm = { resetDistro = null; LinuxEnv.uninstall(d); scope.launch { LinuxEnv.install(d) } }, onDismiss = { resetDistro = null }) }
    removeDistro?.let { d ->
        ConfirmDialog("Remove ${d.label}?", "Deletes this Linux system with every package and file installed in it (projects on your storage are kept).", "Remove", true,
            onConfirm = { LinuxEnv.uninstall(d) }, onDismiss = { removeDistro = null })
    }
    importList?.let { list ->
        val chosen = remember(list) { mutableStateListOf<String>().apply { addAll(list.map { it.id }) } }
        AlertDialog(onDismissRequest = { importList = null }, title = { Text("Import ${list.size} tools") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                list.forEach { t ->
                    Row(Modifier.fillMaxWidth().selectable(t.id in chosen) { if (t.id in chosen) chosen.remove(t.id) else chosen.add(t.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.id in chosen, null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Text(KINDS.first { it.first == t.kind }.second + if (t.kind == "github") " · ${t.source}" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { list.filter { it.id in chosen }.forEach { installTool(it) }; note = "Installing ${chosen.size} tools"; importList = null }) { Text("Install selected") }
        }, dismissButton = { TextButton(onClick = { importList = null }) { Text("Cancel") } })
    }
}

@Composable
private fun JobCard(j: LinuxJobs.Job, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(j.running) }
    Card(Modifier.padding(bottom = 8.dp), onClick = { open = !open }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (j.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(if (j.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                    tint = if (j.ok) LocalExtra.current.success else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(j.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (j.running) j.lastLine.ifEmpty { "Starting…" } else if (j.ok) "Done" else "Failed (exit ${j.exitCode}) · tap for log",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (!j.running) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (open && j.log.isNotEmpty()) {
                val scroll = rememberScrollState()
                LaunchedEffect(j.log.length) { scroll.scrollTo(scroll.maxValue) }
                Text(j.log.takeLast(6000), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = Color(0xFFD7DAE0),
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(max = 260.dp)
                        .background(Color(0xFF0E0F12), RoundedCornerShape(10.dp)).verticalScroll(scroll).padding(10.dp))
            }
        }
    }
}

@Composable
private fun AddToolDialog(onDismiss: () -> Unit, onAdd: (InstalledTool) -> Unit) {
    var kind by remember { mutableStateOf("github") }
    var input by remember { mutableStateOf("") }
    val parsed = remember(kind, input) { if (input.isBlank()) null else ToolSpecs.parse(kind, input) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add tool / skill") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                KINDS.forEachIndexed { i, (k, l) ->
                    SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, KINDS.size)) { Text(l.substringBefore(' '), maxLines = 1) }
                }
            }
            OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(when (kind) { "github" -> "owner/repo or GitHub URL"; "linux" -> "Package names (space separated)"; else -> "pip packages" }) },
                placeholder = { Text(when (kind) { "github" -> "https://github.com/owner/repo"; "linux" -> "ffmpeg imagemagick"; else -> "yt-dlp requests" }) },
                isError = parsed?.isFailure == true,
                supportingText = { parsed?.exceptionOrNull()?.message?.let { Text(it) } ?: Text(when (kind) {
                    "github" -> "Cloned to /opt/tools. SKILL.md files inside become agent skills; requirements.txt is pip-installed."
                    "linux" -> "Installed with apt (Ubuntu) or apk (Alpine)."
                    else -> "Installed with pip3."
                }) })
        }
    }, confirmButton = {
        TextButton(enabled = parsed?.isSuccess == true, onClick = { parsed?.getOrNull()?.let(onAdd) }) { Text("Install") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
