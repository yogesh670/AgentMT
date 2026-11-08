package com.inyogeshwar.agentmt.agent.tools

import java.io.File

/** A project root. Every path a tool touches is resolved inside it; escaping the root is refused. */
class Workspace(root: File) {
    val root: File = root.canonicalFile

    fun resolve(path: String?): File {
        val p = (path ?: "").trim().removePrefix("./").ifEmpty { "." }
        val f = (if (File(p).isAbsolute) File(p) else File(root, p)).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator))
            throw ToolError("Path is outside the project: $path")
        return f
    }

    fun rel(f: File): String = f.canonicalFile.relativeTo(root).path.ifEmpty { "." }

    fun walk(start: File = root, maxFiles: Int = 5000): Sequence<File> =
        start.walkTopDown().onEnter { it == start || it.name !in IGNORED }.filter { it.isFile }.take(maxFiles)

    companion object {
        val IGNORED = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".venv", "__pycache__", "dist", ".next", "target")
    }
}

class ToolError(message: String) : Exception(message)

/** Glob → Regex. Supports **, *, ? and {a,b}. */
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    var inGroup = false
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && glob.getOrNull(i + 1) == '*' -> {
                if (glob.getOrNull(i + 2) == '/') { sb.append("(?:.*/)?"); i += 2 } else { sb.append(".*"); i++ }
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c == '{' -> { sb.append("(?:"); inGroup = true }
            c == '}' && inGroup -> { sb.append(")"); inGroup = false }
            c == ',' && inGroup -> sb.append("|")
            ".()+|^$@%\\[]".contains(c) -> sb.append("\\").append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex(sb.append("$").toString())
}
