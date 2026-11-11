package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import com.inyogeshwar.agentmt.core.linux.ToolSpecs
import com.inyogeshwar.agentmt.core.linux.ToolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Lets the agent install what the user asks for ("install this skill: github.com/…") and records it on the Tools list. */
class InstallTool(private val store: ToolStore) : Tool {
    override val name = "install_tool"
    override val kind = ToolKind.Execute
    override val description = "Install a tool into Linux and add it to the user's Tools list. kind: github (owner/repo or URL; cloned to " +
        "/opt/tools/<name>; SKILL.md folders inside become skills automatically), linux (apt/apk package names), python (pip), " +
        "npm (global Node CLI; Node 22 is installed if needed). Use it when the user asks to install a skill, repo, CLI or package."
    override val schema = schema(p("kind", "string", "github, linux, python or npm"), p("spec", "string", "owner/repo, URL or package names"),
        required = listOf("kind", "spec"))
    override fun target(args: JSONObject) = args.optString("kind") + ":" + args.optString("spec")
    override fun summary(args: JSONObject) = "Installed ${args.optString("spec").take(60)}"
    override fun preview(args: JSONObject, ctx: ToolContext) = "Install ${args.optString("kind")} ${args.optString("spec")} into Linux"

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val distro = LinuxEnv.distro ?: return@withContext ToolResult("Linux is not installed. Ask the user to install it from Menu → Linux.", true)
        val t = ToolSpecs.parse(args.optString("kind"), args.optString("spec")).getOrElse { return@withContext ToolResult(it.message ?: "Bad spec", true) }
        val already = store.tools.value.firstOrNull { it.id == t.id }
        if (already != null && (t.kind != "github" || LinuxEnv.hostPath(t.path).exists())) {
            return@withContext ToolResult("Already installed: ${t.kind} ${t.name}" +
                (if (t.kind == "github") " at ${t.path}" else "") +
                ". Use it directly instead of installing it again. Remove it from Tools first only if you really want to reinstall.")
        }
        val proc = LinuxEnv.process(File(LinuxEnv.hostPath("/").path), ToolSpecs.installScript(t, distro)).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { synchronized(log) { log.append(it).append('\n'); if (log.length > 20_000) log.delete(0, 10_000) } } } }.apply { start() }
        val deadline = System.currentTimeMillis() + 30 * 60_000
        var done = false
        while (isActive && System.currentTimeMillis() < deadline) { if (proc.waitFor(500, TimeUnit.MILLISECONDS)) { done = true; break } }
        if (!done) proc.destroyForcibly()
        reader.join(1000)
        val code = if (done) proc.exitValue() else -1
        val tail = synchronized(log) { log.toString().takeLast(3000) }
        if (code != 0) return@withContext ToolResult("Install failed (exit $code):\n$tail", true)
        val desc = if (t.kind == "github") runCatching { ToolSpecs.describeRepo(LinuxEnv.hostPath(t.path)) }.getOrDefault("") else ""
        store.add(t.copy(description = desc, installedAt = System.currentTimeMillis()))
        val skills = if (t.kind == "github") ToolSpecs.skillDirs(LinuxEnv.hostPath(t.path)).map { it.name } else emptyList()
        ToolResult("Installed ${t.kind} ${t.name}." + (if (t.kind == "github") " Path: ${t.path}." else "") +
            (if (skills.isNotEmpty()) " Skills found: ${skills.joinToString()} (available from the next message via load_skill)." else "") +
            "\n" + tail.takeLast(1200))
    }
}
