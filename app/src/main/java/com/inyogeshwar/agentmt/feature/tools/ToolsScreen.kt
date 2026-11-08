package com.inyogeshwar.agentmt.feature.tools

import androidx.compose.material.icons.automirrored.outlined.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.Decision
import com.inyogeshwar.agentmt.agent.PermissionEngine
import com.inyogeshwar.agentmt.agent.plugins.PluginSummary
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.data.db.PluginEntity
import com.inyogeshwar.agentmt.feature.chat.toolIcon
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(c: AppContainer, onBack: () -> Unit, onMcp: (String?) -> Unit, onSkill: (String?) -> Unit, onRules: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val servers by remember { c.db.mcp().observeAll() }.collectAsState(initial = emptyList())
    val plugins by remember { c.db.plugins().observeAll() }.collectAsState(initial = emptyList())
    val projects by remember { c.db.projects().observeAll() }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val skills = remember(tab, refresh, plugins) { c.skills.all() }
    val extra = LocalExtra.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installSkills by remember { mutableStateOf(false) }
    var installPlugin by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<PluginEntity?>(null) }
    var removing by remember { mutableStateOf<PluginEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val importMd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val base = (uri.lastPathSegment ?: "skill").substringAfterLast('/').substringBeforeLast('.')
            val parsed = SkillStore.parse(text, base, "Yours", "")!!
            c.skills.save(parsed.name, SkillStore.ensureFrontMatter(text, parsed.name, parsed.description))
            message = "Imported skill ${parsed.name}"; refresh++
        }.onFailure { message = it.message ?: "Couldn't import" }
    }

    AppScaffold("Tools & extensions", onBack, floating = {
        when (tab) {
            1 -> ExtendedFloatingActionButton(onClick = { onMcp(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add MCP server") })
            2 -> ExtendedFloatingActionButton(onClick = { onSkill(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New skill") })
            3 -> ExtendedFloatingActionButton(onClick = { installPlugin = true }, icon = { Icon(Icons.Outlined.CloudDownload, null) }, text = { Text("Install plugin") })
        }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 12.dp) {
                listOf("Built-in", "MCP", "Skills", "Plugins").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            message?.let { Text(it, color = extra.success, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            when (tab) {
                0 -> LazyColumn {
                    item { ListRow("Permission rules", "Allow, ask or deny by tool and path", Icons.AutoMirrored.Outlined.Rule, iconTint = MaterialTheme.colorScheme.primary, onClick = onRules) }
                    items(c.engine.builtinTools, key = { it.name }) { t ->
                        val d = PermissionEngine.decide(t.name, t.kind, "*", settings.approvalMode, emptyList()).first
                        ListRow(t.name, t.description, toolIcon(t.name), trailing = {
                            Pill(if (d == Decision.Allow) "Auto" else "Asks", if (d == Decision.Allow) extra.success else extra.warning)
                        })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                1 -> if (servers.isEmpty()) EmptyState(Icons.Outlined.Extension, "No MCP servers",
                    "Connect remote MCP servers (Streamable HTTP) to give the agent more tools — GitHub, databases, search and more.")
                else LazyColumn {
                    items(servers, key = { it.id }) { s ->
                        val n = s.toolsCache.lines().count { it.isNotBlank() }
                        val scopeName = s.projectId?.let { pid -> projects.firstOrNull { it.id == pid }?.name ?: "one project" } ?: "all projects"
                        ListRow(s.name, listOfNotNull(scopeName, if (n > 0) "$n tools" else null, if (s.pluginId != null) "from plugin" else null).joinToString(" · "),
                            Icons.Outlined.Extension, iconTint = MaterialTheme.colorScheme.primary,
                            trailing = {
                                when {
                                    !s.enabled -> Pill("Off", extra.subtle)
                                    s.lastStatus == "ok" -> Pill("Connected", extra.success)
                                    s.lastStatus != null -> Pill("Error", extra.danger)
                                    else -> Pill("New", extra.subtle)
                                }
                            }, onClick = { onMcp(s.id) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
                2 -> LazyColumn {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { installSkills = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.padding(3.dp)); Text("Install") }
                            OutlinedButton(onClick = { importMd.launch(arrayOf("text/markdown", "text/plain", "text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Outlined.NoteAdd, null); Spacer(Modifier.padding(3.dp)); Text("Import .md")
                            }
                        }
                    }
                    items(skills, key = { it.name }) { s ->
                        val on = s.name !in settings.disabledSkills
                        ListRow(s.name, "${s.source} · ${s.description}", Icons.Outlined.AutoStories, trailing = {
                            Switch(on, { v -> c.settings.update { st -> st.copy(disabledSkills = if (v) st.disabledSkills - s.name else st.disabledSkills + s.name) } })
                        }, onClick = { onSkill(s.name) })
                    }
                    item { Text("Skills use the SKILL.md format from Claude Code, anthropics/skills and skills.sh. The agent sees enabled skills' names and descriptions and loads the full text when a task matches. Scripts inside skills are never run.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
                    item { Spacer(Modifier.height(72.dp)) }
                }
                else -> if (plugins.isEmpty()) EmptyState(Icons.Outlined.Inventory2, "No plugins",
                    "Install Claude Code plugins from GitHub or a ZIP. AgentMT uses their skills, slash commands and remote MCP servers. Plugin code and hooks are never executed.")
                else LazyColumn {
                    items(plugins, key = { it.id }) { p ->
                        val sum = PluginSummary.parse(p.summaryJson)
                        ListRow(p.name + "  " + p.version, listOfNotNull(p.description.ifBlank { null }, "${sum.skills.size} skills · ${sum.commands.size} commands · ${sum.mcp.size} MCP").joinToString("\n"),
                            Icons.Outlined.Inventory2, iconTint = MaterialTheme.colorScheme.primary, trailing = {
                                Switch(p.enabled, { v -> if (v) reviewing = p else scope.launch { c.plugins.setEnabled(p, false) } })
                            }, onClick = { reviewing = p })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
    if (installSkills) InstallSkillsDialog(c, projects, onDone = { message = it; refresh++ }) { installSkills = false }
    if (installPlugin) InstallPluginDialog(c, onDone = { reviewing = it }) { installPlugin = false }
    reviewing?.let { p ->
        PluginReviewDialog(p, onEnable = { scope.launch { c.plugins.setEnabled(p, true); c.refreshPluginDirs(); message = "${p.name} enabled" }; reviewing = null },
            onDisable = { scope.launch { c.plugins.setEnabled(p, false); c.refreshPluginDirs() }; reviewing = null },
            onUninstall = { reviewing = null; removing = p }, onDismiss = { reviewing = null })
    }
    removing?.let { p ->
        ConfirmDialog("Uninstall ${p.name}?", "Its skills, commands and MCP servers will be removed.", "Uninstall", true,
            onConfirm = { scope.launch { c.plugins.uninstall(p); c.refreshPluginDirs() } }, onDismiss = { removing = null })
    }
}
