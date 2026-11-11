package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import com.inyogeshwar.agentmt.core.linux.ToolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lets the model look at what the user already installed before it runs a command or installs anything.
 * The answer includes the Tools list and, when a name is given, whether that command is on Linux's PATH.
 */
class InstalledToolsTool(private val store: ToolStore) : Tool {
    override val name = "installed_tools"
    override val kind = ToolKind.Read
    override val description = "Check the user's installed tools before doing anything else. Returns every tool installed in the app " +
        "(GitHub repos, Linux packages, npm CLIs, pip packages) and, for a named command, whether it is already available in Linux. " +
        "Call this first whenever a task needs an external program: if it is installed, use it directly instead of installing or downloading it again."
    override val schema = schema(
        p("need", "string", "Optional program or package to check, e.g. yt-dlp, ffmpeg, node, jadx, apktool, rea"),
    )
    override fun target(args: JSONObject) = args.optString("need").trim().ifBlank { "installed tools" }
    override fun summary(args: JSONObject) = "Checked installed tools" + args.optString("need").trim().takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val all = store.tools.value
        val need = args.optString("need").trim()
        val sb = StringBuilder("# Installed tools\n")
        if (all.isEmpty()) sb.append("(none yet)\n") else all.forEach { t ->
            sb.append("- ").append(t.kind).append(": ").append(t.name)
            when (t.kind) {
                "github" -> sb.append(" — ").append(t.source).append(", at ").append(t.path)
                "npm" -> sb.append(" — global Node CLI")
                "python" -> sb.append(" — pip package")
                "linux" -> sb.append(" — Linux package(s)")
            }
            if (t.description.isNotBlank()) sb.append(" — ").append(t.description)
            sb.append('\n')
        }

        if (need.isBlank()) {
            sb.append("\nPass need=<program> to check whether a command is available.")
            return@withContext ToolResult(sb.toString().trim())
        }

        val cmd = need.substringBefore(' ').substringBefore('/').trim()
        sb.append("\n# Check: ").append(need).append('\n')

        val direct = all.filter { t ->
            t.name.split(' ', ',', '/').any { it.equals(cmd, true) } || t.name.contains(need, true) ||
                (t.kind == "github" && t.source.contains(need, true))
        }
        if (direct.isNotEmpty()) {
            sb.append("Listed in Tools: ").append(direct.joinToString { "${it.kind}:${it.name}" }).append('\n')
        }

        if (!LinuxEnv.ready) {
            sb.append(if (direct.isEmpty()) "Linux is not installed, so PATH cannot be checked. Install Linux, or use the Android shell."
            else "Linux is not installed; use the tool only if it also exists in the Android shell.")
            return@withContext ToolResult(sb.toString().trim())
        }

        val quoted = "'" + cmd.replace("'", "'\\''") + "'"
        val script = "P=\$(command -v $quoted 2>/dev/null || true); " +
            "if [ -n \"\$P\" ]; then echo \"FOUND \$P\"; else " +
            "for d in /opt/tools/* /opt/node/bin/* /usr/local/bin/*; do [ -e \"\$d\" ] && [ \"\$(basename \"\$d\")\" = $quoted ] && { echo \"FOUND \$d\"; exit 0; }; done; echo MISSING; fi"
        val out = runCatching {
            val proc = LinuxEnv.process(File(LinuxEnv.hostPath("/").path), script).redirectErrorStream(true).start()
            proc.waitFor(8, TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readText().trim()
        }.getOrElse { "MISSING" }
        if (out.contains("FOUND")) {
            sb.append(out).append("\nIt is already installed. Use it directly; do not install it again.")
        } else {
            sb.append("Not found on PATH.\nIf a listed tool provides it, read that tool's README and use it. Otherwise install it with install_tool, then use it.")
        }
        ToolResult(sb.toString().trim())
    }
}
