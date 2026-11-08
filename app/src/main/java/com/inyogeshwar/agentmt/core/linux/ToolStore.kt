package com.inyogeshwar.agentmt.core.linux

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A tool installed into the Linux environment: a GitHub repo (like REA), a Linux package, or a Python package. */
data class InstalledTool(
    val kind: String, // github | linux | python
    val name: String,
    val source: String,
    val description: String = "",
    val installedAt: Long = System.currentTimeMillis(),
) {
    val id get() = "$kind:$name"
    /** Where a GitHub tool lives inside Linux. */
    val path get() = if (kind == "github") "/opt/tools/$name" else ""

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("name", name).put("source", source)
        .put("description", description).put("installedAt", installedAt)

    companion object {
        fun fromJson(o: JSONObject) = InstalledTool(o.getString("kind"), o.getString("name"), o.optString("source"),
            o.optString("description"), o.optLong("installedAt", System.currentTimeMillis()))
    }
}

object ToolSpecs {
    private val gh = Regex("""^(?:https?://github\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""")
    private val pkg = Regex("""^[A-Za-z0-9][A-Za-z0-9.+_:-]*$""")
    private val npmPkg = Regex("""^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*(@[A-Za-z0-9._^~<>=-]+)?$""")
    private val pyPkg = Regex("""^[A-Za-z0-9][A-Za-z0-9._\-\[\],=<>!~]*$""")

    /** Parses what the user typed into a tool, or returns an error message. */
    fun parse(kind: String, input: String): Result<InstalledTool> = runCatching {
        val t = input.trim()
        when (kind) {
            "github" -> {
                val m = gh.matchEntire(t) ?: error("Use owner/repo or https://github.com/owner/repo")
                val (owner, repo) = m.destructured
                InstalledTool("github", repo.lowercase(), "https://github.com/$owner/$repo")
            }
            "linux" -> {
                val names = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(names.isNotEmpty() && names.all { pkg.matches(it) }) { "Package names can use letters, digits and . + _ : -" }
                InstalledTool("linux", names.joinToString(" "), names.joinToString(" "))
            }
            "npm" -> {
                val names = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(names.isNotEmpty() && names.all { npmPkg.matches(it) }) { "Not a valid npm package name" }
                InstalledTool("npm", names.joinToString(" "), names.joinToString(" "))
            }
            "python" -> {
                val names = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(names.isNotEmpty() && names.all { pyPkg.matches(it) }) { "Not a valid pip package name" }
                InstalledTool("python", names.joinToString(" "), names.joinToString(" "))
            }
            else -> error("Unknown kind $kind")
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private const val ENSURE_GIT = "command -v git >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y git ca-certificates; else apk add --no-cache git; fi; }"
    private const val ENSURE_PIP = "command -v pip3 >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y python3 python3-pip python3-venv; else apk add --no-cache python3 py3-pip; fi; }"
    private const val PIP = "pip3 install --break-system-packages"

    fun installScript(t: InstalledTool, distro: Distro): String = when (t.kind) {
        "github" -> """
set -e
$ENSURE_GIT
mkdir -p /opt/tools
rm -rf ${q(t.path)}
echo "Cloning ${t.source}"
git clone --depth 1 ${q(t.source)} ${q(t.path)}
cd ${q(t.path)}
if [ -f requirements.txt ]; then echo "Installing Python requirements"; $ENSURE_PIP; $PIP -r requirements.txt || echo "(some Python requirements failed; the agent can retry)"; fi
if [ -f package.json ] && command -v npm >/dev/null 2>&1; then echo "Installing npm packages"; npm install --omit=dev || echo "(npm install failed)"; fi
echo "Installed at ${t.path}"
""".trim()
        "linux" -> "set -e\n" + (if (distro == Distro.Ubuntu) "export DEBIAN_FRONTEND=noninteractive\napt-get update\napt-get install -y ${t.name}" else "apk add --no-cache ${t.name}")
        "python" -> "set -e\n$ENSURE_PIP\n$PIP ${t.name}"
        "npm" -> "set -e\n$NODE22\nnpm install -g ${t.name}\nfor b in /opt/node/bin/*; do ln -sf \"\$b\" /usr/local/bin/; done"
        else -> "echo unknown"
    }

    fun removeScript(t: InstalledTool, distro: Distro): String = when (t.kind) {
        "github" -> "rm -rf ${q(t.path)}"
        "linux" -> distro.removeCmd(t.name)
        "npm" -> "npm uninstall -g ${t.name}"
        "python" -> "pip3 uninstall -y --break-system-packages ${t.name} || pip3 uninstall -y ${t.name}"
        else -> "true"
    }

    /** Short description from a cloned repo's README (first prose line). */
    fun describeRepo(dir: File): String {
        val readme = dir.listFiles()?.firstOrNull { it.name.lowercase().startsWith("readme") } ?: return ""
        return runCatching { readme.readLines().map { it.trim() }
            .firstOrNull { it.length > 20 && !it.startsWith("#") && !it.startsWith("!") && !it.startsWith("[") && !it.startsWith("<") && !it.startsWith("```") }
            ?.take(160) }.getOrNull().orEmpty()
    }

    /** Folders in a repo that hold SKILL.md skills. */
    fun skillDirs(repo: File): List<File> {
        val out = ArrayList<File>()
        if (File(repo, "SKILL.md").isFile) out += repo
        listOf("skills", ".claude/skills", ".agents/skills").forEach { sub ->
            File(repo, sub).listFiles()?.filter { File(it, "SKILL.md").isFile }?.let { out += it }
        }
        return out
    }

    // ---- manifest for backup / share / import ----
    fun manifest(tools: List<InstalledTool>): String = JSONObject().put("format", "agentmt-tools").put("version", 1)
        .put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } }).toString(2)

    /** One-tap bundles shown on the Linux screen. [extra] runs after the listed tools install. */
    data class Kit(val title: String, val about: String, val tools: List<InstalledTool>, val extra: String = "")

    /** Node.js 22 from nodejs.org (Ubuntu's is too old for tools like rea-agents). Skips when node >= 22 is present. */
    val NODE22 = """
if ! node -v 2>/dev/null | grep -qE '^v(2[2-9]|[3-9][0-9])'; then
  command -v xz >/dev/null 2>&1 || (apt-get update && apt-get install -y xz-utils curl ca-certificates) || apk add --no-cache xz curl
  case "${'$'}(uname -m)" in aarch64) A=arm64;; x86_64) A=x64;; armv7l|armv8l) A=armv7l;; *) A=${'$'}(uname -m);; esac
  F=${'$'}(curl -fsSL https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt | grep -o "node-v22[^ ]*-linux-${'$'}A.tar.xz" | head -1)
  echo "Installing ${'$'}F"
  curl -fL "https://nodejs.org/dist/latest-v22.x/${'$'}F" | tar -xJ -C /opt
  ln -sfn "/opt/${'$'}{F%.tar.xz}" /opt/node
  for b in node npm npx corepack; do ln -sf /opt/node/bin/${'$'}b /usr/local/bin/${'$'}b; done
fi
node -v
""".trim()

    val JADX = """
set -e
command -v unzip >/dev/null 2>&1 || apt-get install -y unzip
mkdir -p /opt/jadx && cd /opt/jadx
curl -fL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip
unzip -oq jadx.zip && rm jadx.zip && chmod +x bin/jadx
ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx
echo "jadx 1.5.6 installed"
""".trim()

    val KITS = listOf(
        Kit("Reverse engineering", "REA (rea-agents MCP + skill), apktool, jadx 1.5.6, radare2, Java 17, Node 22",
            listOf(InstalledTool("linux", "apktool radare2 openjdk-17-jdk-headless unzip file binutils curl procps xz-utils", "apt"),
                InstalledTool("npm", "rea-agents", "npm"),
                InstalledTool("github", "rea", "https://github.com/morluto/rea")), JADX),
        Kit("Web dev", "Node.js, npm, Python 3, git: run a local server and open it in the built-in browser",
            listOf(InstalledTool("linux", "nodejs npm python3 python3-pip git curl", "apt"))),
        Kit("Media", "ffmpeg + latest yt-dlp from pip (the apt one is too old for YouTube)",
            listOf(InstalledTool("linux", "ffmpeg python3-pip", "apt"), InstalledTool("python", "yt-dlp", "pip"))),
    )

    /** The whole kit as one script, so it runs as a single job with one log. */
    fun kitScript(k: Kit, distro: Distro): String = (k.tools.map { installScript(it, distro) } + listOfNotNull(k.extra.ifBlank { null }))
        .joinToString("\n") { "(\n$it\n)" } + "\necho 'Kit ready: ${k.title}'"

    fun parseManifest(text: String): List<InstalledTool> {
        val o = JSONObject(text.trim())
        require(o.optString("format") == "agentmt-tools") { "Not an AgentMT tools file" }
        val a = o.getJSONArray("tools")
        return (0 until a.length()).map { InstalledTool.fromJson(a.getJSONObject(it)) }
            .filter { t -> parse(t.kind, if (t.kind == "github") t.source else t.name).isSuccess }
    }
}

class ToolStore(context: Context) {
    private val file = File(context.filesDir, "tools.json")
    private val _tools = MutableStateFlow(load())
    val tools: StateFlow<List<InstalledTool>> = _tools

    private fun load(): List<InstalledTool> = runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).map { InstalledTool.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(l: List<InstalledTool>) { file.writeText(JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()); _tools.value = l }

    @Synchronized fun add(t: InstalledTool) = save(_tools.value.filterNot { it.id == t.id } + t)
    @Synchronized fun remove(t: InstalledTool) = save(_tools.value.filterNot { it.id == t.id })

    /** Skill folders from installed GitHub tools, for SkillStore. */
    fun skillDirs(): List<Pair<String, File>> = if (!LinuxEnv.ready) emptyList() else
        _tools.value.filter { it.kind == "github" }.flatMap { t -> ToolSpecs.skillDirs(LinuxEnv.hostPath(t.path)).map { t.name to it } }

    fun promptNote(): String {
        val l = _tools.value
        if (l.isEmpty() || !LinuxEnv.ready) return ""
        return "\n# Installed tools (inside Linux)\n" + l.joinToString("\n") { t ->
            when (t.kind) {
                "github" -> "- ${t.name}: GitHub ${t.source}, cloned at ${t.path}${if (t.description.isNotBlank()) " — ${t.description}" else ""}. Read its README before using it."
                "linux" -> "- ${t.name}: Linux package(s)"
                "npm" -> "- ${t.name}: global npm package (Node CLI)"
                else -> "- ${t.name}: Python package (pip)"
            }
        }
    }
}
