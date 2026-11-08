package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.agent.llm.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs a command with Android's /system/bin/sh in the project folder. Only toybox utilities are available on stock devices. */
class ShellTool : Tool {
    override val name = "run_command"
    override val kind = ToolKind.Execute
    override val description: String get() = (if (com.inyogeshwar.agentmt.core.termux.TermuxBridge.active)
        "Environment \"termux\" (default now) runs in the user's own Termux app (bash, pkg install …; node, python and git as they installed them). " +
            "Termux sees only shared storage (/storage/emulated/0/…), not AgentMT's private workspace. Output is capped near 100 KB. " else "") +
        (com.inyogeshwar.agentmt.core.linux.LinuxEnv.distro?.let { d ->
        "Run a shell command in the project folder. Default environment is \"linux\": ${d.label} ${d.version} as root (${d.shell}). " +
            "Install what you need with `${d.installHint}`" +
            (if (d.pkg == "apt") " (python3 python3-pip nodejs npm git build-essential ffmpeg…); pip needs --break-system-packages" else " (python3 py3-pip nodejs npm git build-base ffmpeg…)") +
            ". Use environment \"android\" for Android's own sh (toybox). Output is capped."
    } ?: ("Run a shell command in the project folder using Android's sh (toybox: ls, cat, grep, find, sed, mkdir, cp, mv, rm, wc, sort, diff…). " +
            "No git, node or python: the user can install Linux from Menu, Linux, build & tools. Output is capped."))
    override val schema = schema(p("command", "string", "The command line"),
        p("timeout_seconds", "integer", "Max run time, default 60, max 600"),
        p("environment", "string", "termux (default when the user turned it on), linux (built-in) or android"),
        p("background", "boolean", "Start and return after a few seconds without waiting (dev servers, watchers). Manage it with background_process."),
        required = listOf("command"))
    override fun target(args: JSONObject) = args.optString("command")
    override fun summary(args: JSONObject) = "Ran ${target(args).take(80)}"
    override fun preview(args: JSONObject, ctx: ToolContext) = "$ " + args.optString("command")
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val timeout = args.optLong("timeout_seconds", 60).coerceIn(1, 600)
        val termux = com.inyogeshwar.agentmt.core.termux.TermuxBridge
        val env = args.optString("environment", "").ifBlank { if (termux.active) "termux" else "linux" }
        if (env == "termux") {
            if (!termux.ready()) return@withContext ToolResult("Termux is not connected. Ask the user to open Linux → Termux and grant access.", true)
            val dir = ctx.ws.root.absolutePath
            if (!termux.canSee(dir)) return@withContext ToolResult("Termux cannot see this project folder ($dir). Use a project in shared storage (/storage/emulated/0/…), or environment linux.", true)
            val cmd = if (args.optBoolean("background", false))
                "mkdir -p ~/.agentmt/bg; L=~/.agentmt/bg/$(date +%s).log; nohup bash -lc ${termux.shellQuote(args.optString("command"))} > \"\$L\" 2>&1 & sleep 4; echo \"started pid \$! (log \$L)\"; tail -c 3000 \"\$L\""
                else args.optString("command")
            val r = termux.run(cmd, dir, timeout * 1000 + 5000)
                ?: return@withContext ToolResult("Timed out after ${timeout}s waiting for Termux (the command may still be running there).", true)
            val text = r.text()
            return@withContext ToolResult(if (text.length > 30_000) text.take(30_000) + "\n… output truncated" else text, isError = !r.ok)
        }
        val linux = com.inyogeshwar.agentmt.core.linux.LinuxEnv.ready && env != "android"
        val pb = if (linux) com.inyogeshwar.agentmt.core.linux.LinuxEnv.process(ctx.ws.root, args.optString("command"))
            else ProcessBuilder("/system/bin/sh", "-c", args.optString("command")).directory(ctx.ws.root)
        if (args.optBoolean("background", false)) {
            val bg = BackgroundProcs.start(args.optString("command"), pb)
            kotlinx.coroutines.delay(4000)
            return@withContext ToolResult("Started background process #${bg.id} (${if (bg.proc.isAlive) "running" else "exited ${bg.proc.exitValue()}"}).\n" +
                "First output:\n" + BackgroundProcs.tail(bg.id, 3000).ifBlank { "(none yet)" } +
                "\nUse background_process (output / stop) to manage it.")
        }
        val proc = pb.redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { if (out.length < 30_000) out.append(it).append('\n') } }
        }.apply { start() }
        var finished = false
        val deadline = System.currentTimeMillis() + timeout * 1000
        while (System.currentTimeMillis() < deadline && isActive) {
            if (proc.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break }
        }
        if (!finished) proc.destroyForcibly()
        reader.join(1000)
        val code = if (finished) proc.exitValue() else -1
        val text = out.toString().ifEmpty { "(no output)" } + (if (out.length >= 30_000) "\n… output truncated" else "") +
            "\n[exit ${if (finished) code else "timeout after ${timeout}s"}]"
        ToolResult(text, isError = code != 0)
    }
}

class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val kind = ToolKind.Network
    override val description = "Fetch a URL over HTTPS and return its text (HTML is converted to plain text). Max 40 KB."
    override val schema = schema(p("url", "string", "http(s) URL"), required = listOf("url"))
    override fun target(args: JSONObject) = args.optString("url")
    override fun summary(args: JSONObject) = "Fetched ${target(args)}"
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val url = args.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ToolResult("Only http(s) URLs are allowed.", true)
        val r = Http.client.newCall(Http.get(url, mapOf("User-Agent" to "AgentMT/0.2 (Android)"))).await()
        return r.use {
            val body = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
            if (!it.isSuccessful) return ToolResult("HTTP ${it.code}", true)
            val type = it.header("Content-Type").orEmpty()
            val text = if (type.contains("html")) htmlToText(body) else body
            ToolResult(text.take(40_000) + if (text.length > 40_000) "\n… truncated" else "")
        }
    }
}

class SkillTool : Tool {
    override val name = "load_skill"
    override val kind = ToolKind.Read
    override val description = "Load the full instructions of a skill by name. Available skills are listed in the system prompt. " +
        "Pass file to read a supporting file inside the skill folder (paths are listed when the skill loads)."
    override val schema = schema(p("name", "string", "Skill name"), p("file", "string", "Optional supporting file path inside the skill"), required = listOf("name"))
    override fun target(args: JSONObject) = args.optString("name")
    override fun summary(args: JSONObject) = "Loaded skill ${target(args)}"
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val s = ctx.skills.find(args.optString("name")) ?: return ToolResult("No skill named ${args.optString("name")}", true)
        val file = args.optString("file").trim()
        if (file.isNotEmpty()) return ctx.skills.supportingFile(s, file)?.let { ToolResult(it.take(60_000)) } ?: ToolResult("No file $file in ${s.name}", true)
        val extra = ctx.skills.supportingFiles(s).takeIf { it.isNotEmpty() }?.let { "\n\n---\nSupporting files (load with file=…):\n" + it.joinToString("\n") { f -> "- $f" } } ?: ""
        return ToolResult(ctx.skills.content(s) + extra)
    }
}

/** Lets the model keep a visible checklist for the task. */
class TodoTool : Tool {
    override val name = "update_todos"
    override val kind = ToolKind.Read
    override val description = "Replace the task checklist shown to the user. Send the full list every time. status: pending | in_progress | done."
    override val schema: JSONObject = JSONObject("""{"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","done"]}},"required":["content","status"]}}},"required":["todos"]}""")
    override fun target(args: JSONObject) = "checklist"
    override fun summary(args: JSONObject): String {
        val a = args.optJSONArray("todos") ?: return "Updated checklist"
        val done = (0 until a.length()).count { a.optJSONObject(it)?.optString("status") == "done" }
        return "Checklist $done/${a.length()} done"
    }
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val a = args.optJSONArray("todos") ?: return ToolResult("todos must be an array", true)
        val clean = org.json.JSONArray()
        for (i in 0 until minOf(a.length(), 50)) {
            val o = a.optJSONObject(i) ?: continue
            val st = o.optString("status").let { if (it in setOf("pending", "in_progress", "done")) it else "pending" }
            clean.put(JSONObject().put("content", o.optString("content").take(200)).put("status", st))
        }
        ctx.onTodos(clean.toString())
        return ToolResult("Checklist saved (${clean.length()} items).")
    }
}

fun htmlToText(html: String): String = html
    .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
    .replace(Regex("(?i)<li[^>]*>"), "• ")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n\\s*\\n+"), "\n\n")
    .trim()


/** Long-running commands started with run_command background=true. Output goes to a log file per process. */
object BackgroundProcs {
    class Bg(val id: Int, val command: String, val proc: Process, val log: java.io.File)
    private val all = java.util.concurrent.ConcurrentHashMap<Int, Bg>()
    private val next = java.util.concurrent.atomic.AtomicInteger(1)

    fun start(command: String, pb: ProcessBuilder): Bg {
        val id = next.getAndIncrement()
        val log = java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "agentmt-bg-$id.log")
        val proc = pb.redirectErrorStream(true).redirectOutput(log).start()
        return Bg(id, command, proc, log).also { all[id] = it }
    }

    fun tail(id: Int, chars: Int): String = all[id]?.log?.let { f -> runCatching {
        java.io.RandomAccessFile(f, "r").use { r -> val start = (r.length() - chars).coerceAtLeast(0); r.seek(start)
            ByteArray((r.length() - start).toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8) }
    }.getOrDefault("") }.orEmpty()

    fun list(): String = all.values.sortedBy { it.id }.joinToString("\n") { b ->
        "#${b.id} ${if (b.proc.isAlive) "running" else "exited ${b.proc.exitValue()}"}: ${b.command.take(100)}" }.ifEmpty { "No background processes." }

    fun stop(id: Int): Boolean = all[id]?.let { it.proc.destroy(); if (!it.proc.waitFor(2, TimeUnit.SECONDS)) it.proc.destroyForcibly(); true } ?: false
    fun stopAll() = all.keys.forEach { stop(it) }
}

class BackgroundProcessTool : Tool {
    override val name = "background_process"
    override val kind = ToolKind.Execute
    override val description = "Manage commands started with run_command background=true: list, output (recent log) or stop."
    override val schema = schema(p("action", "string", "list, output or stop"), p("id", "integer", "Process number for output/stop"),
        required = listOf("action"))
    override fun target(args: JSONObject) = args.optString("action") + " " + args.optInt("id", 0)
    override fun summary(args: JSONObject) = when (args.optString("action")) { "stop" -> "Stopped process #${args.optInt("id")}"; "output" -> "Read output of #${args.optInt("id")}"; else -> "Listed background processes" }
    override suspend fun run(args: JSONObject, ctx: ToolContext) = when (args.optString("action")) {
        "output" -> ToolResult(BackgroundProcs.tail(args.optInt("id"), 8000).ifBlank { "(no output)" } + "\n\n" + BackgroundProcs.list())
        "stop" -> if (BackgroundProcs.stop(args.optInt("id"))) ToolResult("Stopped #${args.optInt("id")}") else ToolResult("No such process", true)
        else -> ToolResult(BackgroundProcs.list())
    }
}
