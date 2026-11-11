package com.inyogeshwar.agentmt.app

import androidx.compose.runtime.produceState

import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.heightIn

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inyogeshwar.agentmt.app.theme.AgentMTTheme
import com.inyogeshwar.agentmt.core.ui.Logo
import com.inyogeshwar.agentmt.data.db.SessionRow
import com.inyogeshwar.agentmt.feature.chat.ChatScreen
import com.inyogeshwar.agentmt.feature.files.FileEditorScreen
import com.inyogeshwar.agentmt.feature.files.FilesScreen
import com.inyogeshwar.agentmt.feature.onboarding.OnboardingScreen
import com.inyogeshwar.agentmt.feature.providers.ProviderEditScreen
import com.inyogeshwar.agentmt.feature.providers.ProvidersScreen
import com.inyogeshwar.agentmt.feature.runs.RunDetailScreen
import com.inyogeshwar.agentmt.feature.runs.RunsScreen
import com.inyogeshwar.agentmt.feature.settings.RulesScreen
import com.inyogeshwar.agentmt.feature.settings.SettingsScreen
import com.inyogeshwar.agentmt.feature.tools.McpEditScreen
import com.inyogeshwar.agentmt.feature.tools.SkillScreen
import com.inyogeshwar.agentmt.feature.tools.ToolsScreen
import com.inyogeshwar.agentmt.feature.tasks.TasksScreen
import com.inyogeshwar.agentmt.feature.tasks.NewTaskScreen
import com.inyogeshwar.agentmt.feature.routing.RoutingScreen
import com.inyogeshwar.agentmt.feature.routing.ProfileEditScreen
import com.inyogeshwar.agentmt.feature.backup.BackupScreen
import com.inyogeshwar.agentmt.core.ui.TagChip
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.outlined.Checklist
import kotlinx.coroutines.launch
import java.util.Calendar

object R0 {
    const val CHAT = "chat?sid={sid}"
    fun chat(sid: String? = null) = if (sid == null) "chat" else "chat?sid=$sid"
    const val ONBOARD = "onboarding"
    const val FILES = "files/{pid}"
    fun files(pid: String) = "files/$pid"
    const val EDITOR = "editor/{pid}?path={path}"
    fun editor(pid: String, path: String) = "editor/$pid?path=${Uri.encode(path)}"
    const val RUNS = "runs"
    const val RUN = "run/{id}"
    fun run(id: String) = "run/$id"
    const val TOOLS = "tools"
    const val MCP = "mcp?id={id}"
    fun mcp(id: String? = null) = if (id == null) "mcp" else "mcp?id=$id"
    const val SKILL = "skill?name={name}"
    fun skill(name: String? = null) = if (name == null) "skill" else "skill?name=${Uri.encode(name)}"
    const val PROVIDERS = "providers"
    const val PROVIDER = "provider?id={id}&preset={preset}"
    fun provider(id: String? = null, preset: String? = null) = "provider?" + listOfNotNull(id?.let { "id=$it" }, preset?.let { "preset=$it" }).joinToString("&")
    const val SETTINGS = "settings"
    const val RULES = "rules"
    const val TASKS = "tasks"
    const val NEW_TASK = "tasks/new"
    const val ROUTING = "routing"
    const val PROFILE = "profile?id={id}"
    fun profile(id: String? = null) = if (id == null) "profile" else "profile?id=$id"
    const val BACKUP = "backup"
    const val LINUX = "linux"
    const val BROWSER = "browser"
    const val EXTENSIONS = "browser/extensions"
    const val LICENSES = "licenses"
    const val TERMINAL = "terminal?pid={pid}"
    fun terminal(pid: String? = null) = if (pid == null) "terminal" else "terminal?pid=$pid"
}

@Composable
fun AgentMTApp(c: AppContainer) {
    val crashCtx = androidx.compose.ui.platform.LocalContext.current
    var crash by remember { mutableStateOf(com.inyogeshwar.agentmt.core.CrashReporter.pending(crashCtx)) }
    crash?.let { report ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("AgentMT closed unexpectedly") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Here's what happened last time. Copy it and send it to the developer so it can be fixed.", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(report.take(4000), fontFamily = com.inyogeshwar.agentmt.app.theme.Mono, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()))
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString(report)); com.inyogeshwar.agentmt.core.CrashReporter.clear(crashCtx); crash = null
            }) { Text("Copy & close") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { com.inyogeshwar.agentmt.core.CrashReporter.clear(crashCtx); crash = null }) { Text("Dismiss") } },
        )
    }

    val settings by c.settings.state.collectAsState()
    AgentMTTheme(settings.theme, settings.dynamicColor) {
        val nav = rememberNavController()
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(settings.onboarded) {
            if (settings.onboarded && Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNav(c, nav, startOnboarding = !settings.onboarded)
        }
    }
}

@Composable
private fun AppNav(c: AppContainer, nav: NavHostController, startOnboarding: Boolean) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val onChat = entry?.destination?.route == R0.CHAT
    val sessions by c.db.sessions().observeAll().collectAsState(initial = emptyList())
    val live by c.engine.live.collectAsState()
    val links by remember { c.db.tags().observeLinks() }.collectAsState(initial = emptyList())
    var activeSid by remember { mutableStateOf<String?>(null) }
    val back: () -> Unit = { nav.popBackStack() }

    fun goChat(sid: String?) {
        activeSid = sid
        nav.navigate(R0.chat(sid)) { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = false }
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = onChat,
        drawerContent = {
            Drawer(
                sessions = sessions, activeSid = activeSid, running = live.filterValues { it.running }.keys,
                tagsBySession = links.filter { it.kind == "session" }.groupBy({ it.ref }, { it.tag }),
                onNew = { goChat(null) }, onOpen = { goChat(it) },
                onPin = { s -> scope.launch { c.db.sessions().setPinned(s.id, !s.pinned) } },
                onNav = { route -> scope.launch { drawer.close() }; nav.navigate(route) },
                currentProjectId = sessions.firstOrNull { it.id == activeSid }?.projectId ?: sessions.firstOrNull()?.projectId,
            )
        },
    ) {
        NavHost(nav, startDestination = if (startOnboarding) R0.ONBOARD else R0.CHAT,
            enterTransition = { slideInHorizontally { it / 8 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 8 } + fadeOut() },
        ) {
            composable(R0.ONBOARD) {
                OnboardingScreen(c, onDone = { goChat(null) }, onAddProvider = { preset -> nav.navigate(R0.provider(preset = preset)) })
            }
            composable(R0.CHAT, arguments = listOf(navArgument("sid") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                val sid = e.arguments?.getString("sid")
                LaunchedEffect(sid) { activeSid = sid }
                ChatScreen(
                    c, sid,
                    onOpenDrawer = { scope.launch { drawer.open() } },
                    onNewChat = { goChat(null) },
                    onOpenProviders = { nav.navigate(R0.PROVIDERS) },
                    onOpenFiles = { nav.navigate(R0.files(it)) },
                    onSessionCreated = { activeSid = it },
                )
            }
            composable(R0.FILES) { e ->
                val pid = e.arguments!!.getString("pid")!!
                FilesScreen(c, pid, onBack = back, onOpenFile = { nav.navigate(R0.editor(pid, it)) })
            }
            composable(R0.EDITOR, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { e ->
                FileEditorScreen(c, e.arguments!!.getString("pid")!!, e.arguments!!.getString("path").orEmpty(), onBack = back)
            }
            composable(R0.RUNS) { RunsScreen(c, onBack = back, onOpen = { nav.navigate(R0.run(it)) }) }
            composable(R0.RUN) { e -> RunDetailScreen(c, e.arguments!!.getString("id")!!, onBack = back, onOpenChat = { goChat(it) }) }
            composable(R0.TOOLS) {
                ToolsScreen(c, onBack = back, onMcp = { nav.navigate(R0.mcp(it)) }, onSkill = { nav.navigate(R0.skill(it)) },
                    onRules = { nav.navigate(R0.RULES) })
            }
            composable(R0.MCP, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                McpEditScreen(c, e.arguments?.getString("id"), onBack = back)
            }
            composable(R0.SKILL, arguments = listOf(navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                SkillScreen(c, e.arguments?.getString("name"), onBack = back)
            }
            composable(R0.PROVIDERS) { ProvidersScreen(c, onBack = back, onEdit = { id, preset -> nav.navigate(R0.provider(id, preset)) }, onRouting = { nav.navigate(R0.ROUTING) }) }
            composable(R0.TASKS) { TasksScreen(c, onBack = back, onOpen = { goChat(it) }, onNew = { nav.navigate(R0.NEW_TASK) }) }
            composable(R0.NEW_TASK) { NewTaskScreen(c, onBack = back, onCreated = { goChat(it) }) }
            composable(R0.ROUTING) { RoutingScreen(c, onBack = back, onProfile = { nav.navigate(R0.profile(it)) }) }
            composable(R0.PROFILE, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                ProfileEditScreen(c, e.arguments?.getString("id"), onBack = back)
            }
            composable(R0.BACKUP) { BackupScreen(c, onBack = back) }
            composable(R0.TERMINAL, arguments = listOf(navArgument("pid") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                val pid = e.arguments?.getString("pid")
                val dir by produceState<java.io.File?>(null, pid) {
                    value = java.io.File((pid?.let { c.db.projects().get(it) } ?: c.defaultProject()).path)
                }
                dir?.let { com.inyogeshwar.agentmt.feature.terminal.TerminalScreen(c, it, onBack = back, onLinux = { nav.navigate(R0.LINUX) }) }
            }
            composable(R0.LINUX) { com.inyogeshwar.agentmt.feature.linux.LinuxScreen(c, onBack = back, onTerminal = { nav.navigate(R0.terminal(null)) }) }
            composable(R0.LICENSES) { com.inyogeshwar.agentmt.feature.settings.LicensesScreen(onBack = back) }
            composable(R0.BROWSER) { com.inyogeshwar.agentmt.feature.browser.BrowserScreen(c, onBack = back, onExtensions = { nav.navigate(R0.EXTENSIONS) }) }
            composable(R0.EXTENSIONS) { com.inyogeshwar.agentmt.feature.browser.ExtensionsScreen(c, onBack = back,
                onBrowse = { u -> c.browser.view().loadUrl(u); nav.popBackStack() }) }
            composable(R0.PROVIDER, arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("preset") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { e -> ProviderEditScreen(c, e.arguments?.getString("id"), e.arguments?.getString("preset"), onBack = back) }
            composable(R0.SETTINGS) {
                SettingsScreen(c, onBack = back, onProviders = { nav.navigate(R0.PROVIDERS) }, onRules = { nav.navigate(R0.RULES) },
                    onTools = { nav.navigate(R0.TOOLS) }, onRouting = { nav.navigate(R0.ROUTING) }, onBackup = { nav.navigate(R0.BACKUP) },
                    onLinux = { nav.navigate(R0.LINUX) }, onBrowser = { nav.navigate(R0.BROWSER) }, onExtensions = { nav.navigate(R0.EXTENSIONS) },
                    onLicenses = { nav.navigate(R0.LICENSES) })
            }
            composable(R0.RULES) { RulesScreen(c, onBack = back) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(
    sessions: List<SessionRow>, activeSid: String?, running: Set<String>, tagsBySession: Map<String, List<String>>,
    onNew: () -> Unit, onOpen: (String) -> Unit, onPin: (SessionRow) -> Unit, onNav: (String) -> Unit, currentProjectId: String?,
) {
    var q by remember { mutableStateOf("") }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(30.dp)
                Spacer(Modifier.width(10.dp))
                Text("AgentMT", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onNew) { Icon(Icons.Outlined.EditNote, "New chat") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search chats or #tag", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
                }
            }
            val filtered = sessions.filter { s ->
                q.isBlank() || (if (q.startsWith("#")) tagsBySession[s.id].orEmpty().any { it.startsWith(q.drop(1), true) }
                else s.title.contains(q, true) || s.projectName.contains(q, true) || tagsBySession[s.id].orEmpty().any { it.contains(q, true) })
            }
            val groups = filtered.groupBy { s -> if (s.pinned) "Pinned" else dayBucket(s.updatedAt) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                if (filtered.isEmpty()) item {
                    Text(if (sessions.isEmpty()) "Your chats will appear here." else "No chats match.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
                }
                listOf("Pinned", "Today", "Yesterday", "Previous 7 days", "Older").forEach { g ->
                    val list = groups[g] ?: return@forEach
                    item(key = "g$g") {
                        Text(g, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { s ->
                        val selected = s.id == activeSid
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow)
                                .combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { onPin(s) })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (s.kind == "task") { Icon(Icons.Outlined.Checklist, null, Modifier.size(16.dp), tint = com.inyogeshwar.agentmt.feature.chat.statusTint(s.status)); Spacer(Modifier.width(10.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    tagsBySession[s.id].orEmpty().take(2).forEach { TagChip(it, Modifier.padding(start = 6.dp)) }
                                }
                            }
                            if (s.id in running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.6.dp)
                            else if (s.pinned) Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
            val itemColors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            if (currentProjectId != null) NavigationDrawerItem(label = { Text("Files") }, icon = { Icon(Icons.Outlined.FolderOpen, null) }, selected = false,
                onClick = { onNav(R0.files(currentProjectId)) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Tasks") }, icon = { Icon(Icons.Outlined.Checklist, null) }, selected = false,
                onClick = { onNav(R0.TASKS) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Runs") }, icon = { Icon(Icons.Outlined.History, null) }, selected = false,
                badge = { if (running.isNotEmpty()) Text("${running.size} live", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall) },
                onClick = { onNav(R0.RUNS) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Terminal") }, icon = { Icon(Icons.Outlined.Terminal, null) }, selected = false,
                onClick = { onNav(R0.terminal(currentProjectId)) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Browser") }, icon = { Icon(Icons.Outlined.Language, null) }, selected = false,
                onClick = { onNav(R0.BROWSER) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Linux & tools") }, icon = { Icon(Icons.Outlined.Memory, null) }, selected = false,
                onClick = { onNav(R0.LINUX) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Tools, skills & plugins") }, icon = { Icon(Icons.Outlined.Extension, null) }, selected = false,
                onClick = { onNav(R0.TOOLS) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Outlined.Settings, null) }, selected = false,
                onClick = { onNav(R0.SETTINGS) }, modifier = Modifier.padding(horizontal = 12.dp), colors = itemColors)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun dayBucket(ts: Long): String {
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = cal.timeInMillis
    return when {
        ts >= today -> "Today"
        ts >= today - 86_400_000 -> "Yesterday"
        ts >= today - 7 * 86_400_000L -> "Previous 7 days"
        else -> "Older"
    }
}
