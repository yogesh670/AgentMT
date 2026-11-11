package com.inyogeshwar.agentmt.app

import android.content.Context
import android.os.Environment
import com.inyogeshwar.agentmt.agent.AgentEngine
import com.inyogeshwar.agentmt.agent.plugins.CommandStore
import com.inyogeshwar.agentmt.agent.plugins.PluginManager
import com.inyogeshwar.agentmt.agent.routing.ModelRouter
import com.inyogeshwar.agentmt.agent.skills.GitHubFetcher
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.data.backup.WorkspaceBackup
import com.inyogeshwar.agentmt.data.db.TagEntity
import com.inyogeshwar.agentmt.data.db.TagLinkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.inyogeshwar.agentmt.data.db.AppDatabase
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import com.inyogeshwar.agentmt.data.db.SessionEntity
import com.inyogeshwar.agentmt.data.prefs.SettingsStore
import com.inyogeshwar.agentmt.data.secure.SecretVault
import java.io.File
import java.util.UUID

/** Manual dependency container (see DECISIONS.md, D-004). */
class AppContainer(val context: Context) {
    val db = AppDatabase.build(context)
    val vault = SecretVault(context)
    val mcpOAuth = com.inyogeshwar.agentmt.agent.mcp.McpOAuth(context, vault)
    val settings = SettingsStore(context)
    val skills = SkillStore(context)
    val commands = CommandStore(context)
    val router = ModelRouter(db, vault, settings)
    val engine = AgentEngine(context, db, vault, settings, skills, router)
    val github = GitHubFetcher { vault.get("github") }
    val plugins = PluginManager(context, db, github)
    val backup = WorkspaceBackup(context, db, vault, settings) { privateProjectsDir }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val terminals = com.inyogeshwar.agentmt.feature.terminal.TerminalManager(context)
    val linuxJobs = com.inyogeshwar.agentmt.core.linux.LinuxJobs(context)
    val toolStore = com.inyogeshwar.agentmt.core.linux.ToolStore(context)
    val browser = com.inyogeshwar.agentmt.feature.browser.BrowserController(context, com.inyogeshwar.agentmt.feature.browser.UserScriptStore(context))

    init {
        com.inyogeshwar.agentmt.core.linux.LinuxEnv.init(context)
        runCatching { com.inyogeshwar.agentmt.core.linux.AndroidBridge.init(context) }
        com.inyogeshwar.agentmt.agent.tools.BrowserHub.controller = browser
        skills.toolSkillDirs = { toolStore.skillDirs() }
        engine.extraTools = listOf(
            com.inyogeshwar.agentmt.agent.tools.InstalledToolsTool(toolStore),
            com.inyogeshwar.agentmt.agent.tools.InstallTool(toolStore),
        )
        engine.oauthToken = { url -> if (url.startsWith("http")) runCatching { mcpOAuth.accessToken(url) }.getOrNull() else null }
        engine.envNote = { toolStore.promptNote() +
            (if (com.inyogeshwar.agentmt.core.linux.LinuxEnv.ready) "\n# Android from Linux\nInside Linux these commands reach the phone: agentmt-open <url|file> (open in an app), agentmt-share <file|text>, agentmt-clipboard-get, agentmt-clipboard-set <text>, agentmt-notify <title>, agentmt-toast <text>, agentmt-install <file.apk> (open Android's installer). Long-running servers: run_command with background=true, then open http://localhost:PORT with the browser tool.\n" else "") }
        appScope.launch { db.plugins().observeAll().collect { refreshPluginDirs() } }
    }

    suspend fun refreshPluginDirs() {
        val dirs = plugins.enabledDirs()
        skills.pluginDirs = dirs; commands.pluginDirs = dirs
    }

    suspend fun addTag(name: String, kind: String, ref: String) {
        val tag = normalizeTag(name) ?: return
        db.tags().insertTag(TagEntity(tag, TAG_COLORS[(tag.hashCode() and 0x7fffffff) % TAG_COLORS.size], System.currentTimeMillis()))
        db.tags().link(TagLinkEntity(tag, kind, ref))
    }

    suspend fun removeTag(name: String, kind: String, ref: String) = db.tags().unlink(TagLinkEntity(name, kind, ref))

    /** App-private projects need no storage permission. */
    val privateProjectsDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "projects").apply { mkdirs() }

    fun hasAllFilesAccess(): Boolean = com.inyogeshwar.agentmt.BuildConfig.FULL &&
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { Environment.isExternalStorageManager() }.getOrDefault(false) else true

    suspend fun openProject(path: String, name: String? = null): ProjectEntity {
        val now = System.currentTimeMillis()
        val existing = db.projects().byPath(path)
        val p = existing?.copy(lastOpenedAt = now) ?: ProjectEntity(UUID.randomUUID().toString(), name ?: File(path).name.ifEmpty { "Project" }, path, now, now)
        db.projects().upsert(p)
        return p
    }

    suspend fun createPrivateProject(name: String): ProjectEntity {
        val slug = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "").replace(' ', '-').ifEmpty { "project" }
        var dir = File(privateProjectsDir, slug); var i = 2
        while (dir.exists()) dir = File(privateProjectsDir, "$slug-${i++}")
        dir.mkdirs()
        return openProject(dir.path, name.trim().ifEmpty { dir.name })
    }

    /** The default scratch project used when the user starts chatting without picking a folder. */
    suspend fun defaultProject(): ProjectEntity {
        val dir = File(privateProjectsDir, "playground").apply { mkdirs() }
        return openProject(dir.path, "Playground")
    }

    suspend fun newSession(projectId: String, providerId: String? = null, modelId: String? = null): String {
        val s = settings.state.value
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        db.sessions().upsert(SessionEntity(id, projectId, "New chat", providerId ?: s.defaultProviderId, modelId ?: s.defaultModelId, now, now,
            profileId = if (providerId == null) s.defaultProfileId else null))
        return id
    }

    suspend fun newTask(
        projectId: String, title: String, instructions: String, tags: List<String>,
        providerId: String?, modelId: String?, profileId: String?, skills: String,
    ): String {
        val s = settings.state.value
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        db.sessions().upsert(SessionEntity(id, projectId, title.trim().ifEmpty { "Untitled task" },
            providerId ?: s.defaultProviderId, modelId ?: s.defaultModelId, now, now, kind = "task", status = "active",
            instructions = instructions.trim(), profileId = profileId, skills = skills))
        tags.forEach { addTag(it, "session", id) }
        return id
    }
}

/** Converts a SAF tree URI on primary storage to a filesystem path (requires All files access to use). */
fun treeUriToPath(uri: android.net.Uri): String? {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val base = when (parts[0]) {
        "primary" -> Environment.getExternalStorageDirectory().path
        "home" -> Environment.getExternalStorageDirectory().path + "/Documents"
        else -> "/storage/${parts[0]}"
    }
    return if (parts.size > 1 && parts[1].isNotEmpty()) "$base/${parts[1]}" else base
}

fun normalizeTag(raw: String): String? = raw.trim().removePrefix("#").lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32).ifEmpty { null }

/** Muted, distinguishable tag colours that read on both themes. */
val TAG_COLORS = listOf(0xFF60A5FA, 0xFF34D399, 0xFFF59E0B, 0xFFF472B6, 0xFFA78BFA, 0xFF22D3EE, 0xFFF87171, 0xFFA3E635).map { it.toInt() }
