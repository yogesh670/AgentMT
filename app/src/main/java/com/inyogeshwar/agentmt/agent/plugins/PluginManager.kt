package com.inyogeshwar.agentmt.agent.plugins

import android.content.Context
import android.net.Uri
import com.inyogeshwar.agentmt.agent.skills.GitHubFetcher
import com.inyogeshwar.agentmt.agent.skills.RepoRef
import com.inyogeshwar.agentmt.data.db.AppDatabase
import com.inyogeshwar.agentmt.data.db.McpServerEntity
import com.inyogeshwar.agentmt.data.db.PluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class PluginSummary(
    val skills: List<String>, val commands: List<String>, val mcp: List<Pair<String, String>>, val unsupported: List<String>,
) {
    fun toJson() = JSONObject().put("skills", JSONArray(skills)).put("commands", JSONArray(commands))
        .put("mcp", JSONArray().apply { mcp.forEach { put(JSONObject().put("name", it.first).put("url", it.second)) } })
        .put("unsupported", JSONArray(unsupported))

    companion object {
        fun parse(json: String): PluginSummary = runCatching {
            val o = JSONObject(json)
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val mcp = o.optJSONArray("mcp")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { m -> m.optString("name") to m.optString("url") } } }.orEmpty()
            PluginSummary(arr("skills"), arr("commands"), mcp, arr("unsupported"))
        }.getOrDefault(PluginSummary(emptyList(), emptyList(), emptyList(), emptyList()))
    }
}

data class MarketplaceItem(val name: String, val description: String, val path: String)

/**
 * Installs plugins in the Claude Code plugin layout (.claude-plugin/plugin.json + skills/, commands/, .mcp.json).
 * AgentMT never runs plugin code: hooks, scripts, agents and stdio MCP servers are recorded as unsupported.
 * New plugins are installed disabled and only take effect after the user reviews and enables them.
 */
class PluginManager(private val context: Context, private val db: AppDatabase, private val github: GitHubFetcher) {
    val root: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    /** Lists plugins in a marketplace repo, or returns null when [ref] points at a single plugin. */
    suspend fun marketplace(ref: RepoRef): List<MarketplaceItem>? {
        val top = github.list(ref)
        val hasPluginJson = top.any { it.name == ".claude-plugin" } &&
            runCatching { github.list(ref, "${ref.path}/.claude-plugin".trim('/')) }.getOrDefault(emptyList()).any { it.name == "plugin.json" }
        val mk = runCatching { github.list(ref, "${ref.path}/.claude-plugin".trim('/')) }.getOrDefault(emptyList()).firstOrNull { it.name == "marketplace.json" }
        if (mk?.downloadUrl != null && !hasPluginJson) {
            val o = JSONObject(String(github.download(mk.downloadUrl)))
            val arr = o.optJSONArray("plugins") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { p ->
                val src = p.opt("source")
                val path = (src as? String)?.removePrefix("./")?.trim('/') ?: return@mapNotNull null // remote sources not supported yet
                MarketplaceItem(p.optString("name"), p.optString("description"), listOf(ref.path, path).filter { it.isNotBlank() }.joinToString("/"))
            }
        }
        return null
    }

    suspend fun installFromGitHub(ref: RepoRef): PluginEntity {
        val files = github.downloadTree(ref, ref.path)
        return install(files, "github:${ref.display}")
    }

    suspend fun installFromZip(uri: Uri): PluginEntity = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, ByteArray>(); var total = 0L
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || e.name.contains("..")) continue
                    val bytes = z.readBytes(); total += bytes.size
                    if (total > 20_000_000) throw IllegalStateException("Plugin archive is larger than 20 MB")
                    files[e.name] = bytes
                }
            }
        }
        // Strip a single top-level folder (GitHub "Download ZIP" style).
        val prefix = files.keys.map { it.substringBefore('/') }.distinct().singleOrNull()?.takeIf { p -> files.keys.all { it.startsWith("$p/") } }
        val norm = if (prefix != null) files.mapKeys { it.key.removePrefix("$prefix/") } else files
        install(norm, "zip")
    }

    private suspend fun install(files: Map<String, ByteArray>, source: String): PluginEntity = withContext(Dispatchers.IO) {
        val manifestBytes = files[".claude-plugin/plugin.json"] ?: files["plugin.json"]
            ?: throw IllegalStateException("No .claude-plugin/plugin.json found. Is this a Claude Code plugin?")
        val m = JSONObject(String(manifestBytes))
        val name = m.optString("name").ifBlank { throw IllegalStateException("plugin.json has no name") }
        val id = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-")
        val dir = File(root, id)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val skills = LinkedHashSet<String>(); val commands = ArrayList<String>(); val unsupported = LinkedHashSet<String>()
        for ((path, bytes) in files) {
            when {
                path.startsWith("skills/") -> { skills += path.removePrefix("skills/").substringBefore('/'); write(dir, path, bytes) }
                path.startsWith("commands/") && path.endsWith(".md") -> { commands += path.removePrefix("commands/").removeSuffix(".md"); write(dir, path, bytes) }
                path.startsWith("hooks/") -> unsupported += "hooks (never executed)"
                path.startsWith("agents/") -> unsupported += "sub-agents"
                path.startsWith("scripts/") || path.startsWith("bin/") -> unsupported += "scripts (never executed)"
                path == ".mcp.json" || path == ".claude-plugin/plugin.json" || path.endsWith("README.md") || path == "LICENSE" -> write(dir, path, bytes)
            }
        }
        val mcpJson = files[".mcp.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: m.optJSONObject("mcpServers")?.let { JSONObject().put("mcpServers", it) }
        val mcp = ArrayList<Pair<String, String>>()
        db.mcp().deleteForPlugin(id)
        mcpJson?.optJSONObject("mcpServers")?.let { servers ->
            servers.keys().forEach { key ->
                val s = servers.getJSONObject(key)
                val url = s.optString("url")
                if (url.startsWith("http") && !url.contains("\${")) {
                    mcp += key to url
                    db.mcp().upsert(McpServerEntity(UUID.randomUUID().toString(), "$name · $key", url, enabled = false,
                        createdAt = System.currentTimeMillis(), pluginId = id))
                } else unsupported += "MCP server \"$key\" (${if (s.has("command")) "local command" else "needs configuration"})"
            }
        }
        val summary = PluginSummary(skills.filter { it.isNotBlank() }, commands, mcp, unsupported.toList())
        val entity = PluginEntity(id, name, m.optString("version", "0.0.0"), m.optString("description"), source, enabled = false,
            dir = dir.name, summaryJson = summary.toJson().toString(), installedAt = System.currentTimeMillis())
        db.plugins().upsert(entity)
        entity
    }

    private fun write(dir: File, rel: String, bytes: ByteArray) {
        val f = File(dir, rel).canonicalFile
        if (!f.path.startsWith(dir.canonicalPath + File.separator)) return
        f.parentFile?.mkdirs(); f.writeBytes(bytes)
    }

    suspend fun setEnabled(p: PluginEntity, enabled: Boolean) {
        db.plugins().upsert(p.copy(enabled = enabled))
        db.mcp().forPlugin(p.id).forEach { db.mcp().upsert(it.copy(enabled = enabled)) }
    }

    suspend fun uninstall(p: PluginEntity) {
        db.mcp().deleteForPlugin(p.id); db.plugins().delete(p.id); File(root, p.dir).deleteRecursively()
    }

    suspend fun enabledDirs(): List<Pair<String, File>> = db.plugins().all().filter { it.enabled }.map { it.name to File(root, it.dir) }
}
