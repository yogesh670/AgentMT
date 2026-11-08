package com.inyogeshwar.agentmt.agent.plugins

import android.content.Context
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import java.io.File

/** A slash command: a Markdown prompt template ($ARGUMENTS, $1…$9). */
data class SlashCommand(val name: String, val description: String, val body: String, val source: String)

class CommandStore(private val context: Context) {
    val userDir: File get() = File(context.filesDir, "commands").apply { mkdirs() }
    @Volatile var pluginDirs: List<Pair<String, File>> = emptyList()

    fun all(projectRoot: File?): List<SlashCommand> {
        val out = LinkedHashMap<String, SlashCommand>()
        BUILTIN.forEach { out[it.name] = it }
        val dirs = ArrayList<Pair<File, String>>()
        pluginDirs.forEach { (n, d) -> dirs += File(d, "commands") to n }
        dirs += userDir to "Yours"
        projectRoot?.let { dirs += File(it, ".claude/commands") to "Project"; dirs += File(it, ".agentmt/commands") to "Project" }
        for ((d, src) in dirs) d.listFiles()?.filter { it.extension == "md" }?.forEach { f ->
            val text = f.readText()
            val desc = SkillStore.parse(text, f.nameWithoutExtension, src, f.path)?.description.orEmpty()
            val body = if (text.startsWith("---")) text.substringAfter("\n---").trimStart() else text
            out[f.nameWithoutExtension] = SlashCommand(f.nameWithoutExtension, desc, body, src)
        }
        return out.values.sortedBy { it.name }
    }

    companion object {
        val BUILTIN = listOf(
            SlashCommand("init", "Create or update AGENTS.md for this project", "Analyze this project and create (or improve) AGENTS.md in the project root. Include: what the project is, how to build, run and test it (exact commands), the folder layout, code style and naming conventions, and anything an agent must not do. Keep it under 150 lines and only write facts you verified by reading files. \$ARGUMENTS", "Built-in"),
            SlashCommand("plan", "Plan only: read and propose, change nothing", "Plan mode. Do not edit files or run commands that change anything. Read what you need, then give a numbered implementation plan for: \$ARGUMENTS. List the files you would touch and the risks.", "Built-in"),
            SlashCommand("review", "Review recent changes for bugs and risks", "Review the code in this project that relates to: \$ARGUMENTS. Look for bugs, security problems and regressions. Use the code-review skill. Report findings by severity with file:line.", "Built-in"),
            SlashCommand("explain", "Explain the project or a file", "Explain \$ARGUMENTS in this project: what it does, how the pieces fit, and where to start reading.", "Built-in"),
            SlashCommand("debug", "Systematically debug a problem", "Debug this problem: \$ARGUMENTS. Use the systematic-debugging skill: reproduce, find the root cause, fix it, and verify.", "Built-in"),
            SlashCommand("docs", "Write or update documentation", "Write clear documentation for \$ARGUMENTS. Update README.md if it exists.", "Built-in"),
            SlashCommand("test", "Add tests", "Add focused tests for \$ARGUMENTS following the project's existing test style.", "Built-in"),
        )

        fun expand(c: SlashCommand, args: String): String {
            val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var s = c.body.replace("\$ARGUMENTS", args.trim().ifEmpty { "the current project" })
            for (i in 1..9) s = s.replace("\$$i", parts.getOrElse(i - 1) { "" })
            return s
        }
    }
}
