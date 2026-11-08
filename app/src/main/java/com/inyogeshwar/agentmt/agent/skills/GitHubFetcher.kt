package com.inyogeshwar.agentmt.agent.skills

import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.agent.llm.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Parsed reference like owner/repo[/path][@ref], from a GitHub or skills.sh URL or shorthand. */
data class RepoRef(val owner: String, val repo: String, val path: String = "", val ref: String? = null) {
    val display get() = "$owner/$repo" + if (path.isNotEmpty()) "/$path" else ""

    companion object {
        fun parse(input: String): RepoRef? {
            var s = input.trim().removeSuffix("/").removeSuffix(".git")
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            var ref: String? = null
            when {
                s.startsWith("github.com/") -> {
                    s = s.removePrefix("github.com/")
                    val m = Regex("^([^/]+)/([^/]+)/(?:tree|blob)/([^/]+)/?(.*)$").find(s)
                    if (m != null) return RepoRef(m.groupValues[1], m.groupValues[2], m.groupValues[4].removeSuffix("/SKILL.md"), m.groupValues[3])
                }
                s.startsWith("skills.sh/") -> s = s.removePrefix("skills.sh/")
                s.startsWith("npx skills add ") -> s = s.removePrefix("npx skills add ").trim()
            }
            if ('@' in s) { ref = s.substringAfter('@'); s = s.substringBefore('@') }
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return RepoRef(parts[0], parts[1], parts.drop(2).joinToString("/"), ref)
        }
    }
}

data class RemoteEntry(val name: String, val path: String, val type: String, val downloadUrl: String?, val size: Long)

/** Read-only GitHub contents API client (unauthenticated: 60 requests/hour; a token raises the limit). */
class GitHubFetcher(private val token: () -> String?) {
    private fun headers() = buildMap {
        put("Accept", "application/vnd.github+json"); put("User-Agent", "AgentMT")
        token()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    suspend fun list(r: RepoRef, path: String = r.path): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${r.owner}/${r.repo}/contents/${path.trim('/')}" + (r.ref?.let { "?ref=$it" } ?: "")
        Http.client.newCall(Http.get(url, headers())).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 && body.contains("rate limit")) throw IllegalStateException("GitHub rate limit reached. Add a GitHub token in Settings, or try again in an hour.")
            if (resp.code == 404) throw IllegalStateException("Not found on GitHub: ${r.owner}/${r.repo}/$path")
            if (!resp.isSuccessful) throw IllegalStateException("GitHub HTTP ${resp.code}")
            val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                RemoteEntry(it.optString("name"), it.optString("path"), it.optString("type"), it.optString("download_url").ifBlank { null }, it.optLong("size"))
            }
        }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        Http.client.newCall(Http.get(url, headers())).await().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
            resp.body!!.bytes()
        }
    }

    /** Downloads every file under [path] (max 80 files, 4 MB total). Keys are paths relative to [path]. */
    suspend fun downloadTree(r: RepoRef, path: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>(); var total = 0L
        suspend fun walk(p: String) {
            for (e in list(r, p)) {
                if (out.size >= 80 || total > 4_000_000) return
                when (e.type) {
                    "dir" -> walk(e.path)
                    "file" -> e.downloadUrl?.let { u ->
                        val bytes = download(u); total += bytes.size
                        out[e.path.removePrefix(path.trim('/')).trimStart('/')] = bytes
                    }
                }
            }
        }
        walk(path)
        return out
    }

    /** Finds skill folders (folders that contain SKILL.md) at [r.path] or one/two levels below (e.g. skills/<name>). */
    suspend fun findSkills(r: RepoRef): List<RemoteEntry> {
        val top = list(r)
        if (top.any { it.name == "SKILL.md" }) return listOf(RemoteEntry(r.path.substringAfterLast('/').ifEmpty { r.repo }, r.path, "dir", null, 0))
        val found = ArrayList<RemoteEntry>()
        val dirs = top.filter { it.type == "dir" }
        val skillsDir = dirs.firstOrNull { it.name == "skills" }
        val scan = if (skillsDir != null) list(r, skillsDir.path).filter { it.type == "dir" } else dirs
        for (d in scan.take(40)) {
            val inside = runCatching { list(r, d.path) }.getOrDefault(emptyList())
            if (inside.any { it.name == "SKILL.md" }) found += d
        }
        return found
    }
}
