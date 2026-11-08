package com.inyogeshwar.agentmt.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MAX_READ_BYTES = 512 * 1024

class ListDirTool : Tool {
    override val name = "list_dir"
    override val kind = ToolKind.Read
    override val description = "List files and folders in a project directory. Folders end with '/'. Use depth>1 to list nested levels."
    override val schema = schema(p("path", "string", "Directory relative to the project root. Default '.'"),
        p("depth", "integer", "How many levels to list (1-4). Default 1"))
    override fun target(args: JSONObject) = args.optString("path", ".").ifEmpty { "." }
    override fun summary(args: JSONObject) = "Listed ${target(args)}"
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val dir = ctx.ws.resolve(args.optString("path", "."))
        if (!dir.isDirectory) return@withContext ToolResult("Not a directory: ${ctx.ws.rel(dir)}", true)
        val depth = args.optInt("depth", 1).coerceIn(1, 4)
        val out = StringBuilder(); var n = 0
        fun walk(d: java.io.File, level: Int, indent: String) {
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (k in kids) {
                if (n++ > 800) { out.append("$indent… (truncated)\n"); return }
                if (k.isDirectory) {
                    out.append(indent).append(k.name).append("/\n")
                    if (level < depth && k.name !in Workspace.IGNORED) walk(k, level + 1, "$indent  ")
                } else out.append(indent).append(k.name).append("  (").append(humanSize(k.length())).append(")\n")
            }
        }
        walk(dir, 1, "")
        ToolResult(out.toString().ifEmpty { "(empty directory)" })
    }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val kind = ToolKind.Read
    override val description = "Read a text file. Returns numbered lines. Use offset/limit (1-based lines) for large files."
    override val schema = schema(p("path", "string", "File path relative to the project root"),
        p("offset", "integer", "First line to read, 1-based"), p("limit", "integer", "Max lines (default 2000)"),
        required = listOf("path"))
    override fun target(args: JSONObject) = args.optString("path")
    override fun summary(args: JSONObject) = "Read ${target(args)}"
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val f = ctx.ws.resolve(args.optString("path"))
        if (!f.exists()) return@withContext ToolResult("File not found: ${args.optString("path")}", true)
        if (f.isDirectory) return@withContext ToolResult("That is a directory. Use list_dir.", true)
        if (f.length() > MAX_READ_BYTES * 8) return@withContext ToolResult("File is too large (${humanSize(f.length())}).", true)
        val lines = f.readLines()
        val from = (args.optInt("offset", 1).coerceAtLeast(1)) - 1
        val limit = args.optInt("limit", 2000).coerceIn(1, 5000)
        val slice = lines.drop(from).take(limit)
        val body = slice.mapIndexed { i, l -> "${(from + i + 1).toString().padStart(5)}  ${l.take(2000)}" }.joinToString("\n")
        val more = if (from + slice.size < lines.size) "\n… ${lines.size - from - slice.size} more lines (use offset)" else ""
        ToolResult(body.ifEmpty { "(empty file)" } + more)
    }
}

class WriteFileTool : Tool {
    override val name = "write_file"
    override val kind = ToolKind.Write
    override val description = "Create or overwrite a file with the full content given. Parent folders are created. Prefer edit_file for small changes."
    override val schema = schema(p("path", "string", "File path relative to the project root"),
        p("content", "string", "Complete file content"), required = listOf("path", "content"))
    override fun target(args: JSONObject) = args.optString("path")
    override fun summary(args: JSONObject) = "Wrote ${target(args)}"
    override fun preview(args: JSONObject, ctx: ToolContext): String {
        val f = runCatching { ctx.ws.resolve(args.optString("path")) }.getOrNull()
        val new = args.optString("content")
        return if (f?.exists() == true) unifiedDiff(f.readText(), new) else new.lines().take(60).joinToString("\n") { "+ $it" }
    }
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val f = ctx.ws.resolve(args.optString("path"))
        val existed = f.exists()
        f.parentFile?.mkdirs()
        val content = args.optString("content")
        f.writeText(content)
        ToolResult("${if (existed) "Updated" else "Created"} ${ctx.ws.rel(f)} (${content.lines().size} lines)", changedPath = ctx.ws.rel(f))
    }
}

class EditFileTool : Tool {
    override val name = "edit_file"
    override val kind = ToolKind.Write
    override val description = "Replace an exact string in a file. old_string must match exactly once unless replace_all is true. Read the file first."
    override val schema = schema(p("path", "string", "File path relative to the project root"),
        p("old_string", "string", "Exact text to replace, including whitespace"),
        p("new_string", "string", "Replacement text"), p("replace_all", "boolean", "Replace every occurrence"),
        required = listOf("path", "old_string", "new_string"))
    override fun target(args: JSONObject) = args.optString("path")
    override fun summary(args: JSONObject) = "Edited ${target(args)}"
    override fun preview(args: JSONObject, ctx: ToolContext): String {
        val f = runCatching { ctx.ws.resolve(args.optString("path")) }.getOrNull()
        val old = args.optString("old_string"); val new = args.optString("new_string")
        val text = f?.takeIf { it.exists() }?.readText()
        return if (text != null && text.contains(old)) unifiedDiff(text, text.replaceFirst(old, new))
        else old.lines().joinToString("\n") { "- $it" } + "\n" + new.lines().joinToString("\n") { "+ $it" }
    }
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val f = ctx.ws.resolve(args.optString("path"))
        if (!f.exists()) return@withContext ToolResult("File not found: ${args.optString("path")}", true)
        val old = args.optString("old_string"); val new = args.optString("new_string")
        val text = f.readText()
        val count = countOccurrences(text, old)
        if (count == 0) return@withContext ToolResult("old_string was not found in ${ctx.ws.rel(f)}. Read the file and copy the text exactly.", true)
        val all = args.optBoolean("replace_all", false)
        if (count > 1 && !all) return@withContext ToolResult("old_string matches $count places. Add more context or set replace_all.", true)
        f.writeText(if (all) text.replace(old, new) else text.replaceFirst(old, new))
        ToolResult("Edited ${ctx.ws.rel(f)} ($count replacement${if (count > 1) "s" else ""})", changedPath = ctx.ws.rel(f))
    }
}

class GlobTool : Tool {
    override val name = "glob"
    override val kind = ToolKind.Read
    override val description = "Find files by glob pattern, e.g. '**/*.kt' or 'src/**/{a,b}.ts'. Ignores build and VCS folders."
    override val schema = schema(p("pattern", "string", "Glob pattern relative to the project root"), required = listOf("pattern"))
    override fun target(args: JSONObject) = args.optString("pattern")
    override fun summary(args: JSONObject) = "Searched files ${target(args)}"
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val rx = globToRegex(args.optString("pattern"))
        val hits = ctx.ws.walk().map { ctx.ws.rel(it) }.filter { rx.matches(it) }.take(300).toList()
        ToolResult(if (hits.isEmpty()) "No files match." else hits.joinToString("\n"))
    }
}

class GrepTool : Tool {
    override val name = "grep"
    override val kind = ToolKind.Read
    override val description = "Search file contents with a regular expression. Returns path:line: text. Optionally limit files with an include glob."
    override val schema = schema(p("pattern", "string", "Regular expression"),
        p("include", "string", "Glob of files to search, e.g. '**/*.kt'"), p("ignore_case", "boolean", "Case-insensitive"),
        required = listOf("pattern"))
    override fun target(args: JSONObject) = args.optString("pattern")
    override fun summary(args: JSONObject) = "Searched for \"${target(args)}\""
    override suspend fun run(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val rx = runCatching {
            if (args.optBoolean("ignore_case")) Regex(args.optString("pattern"), RegexOption.IGNORE_CASE) else Regex(args.optString("pattern"))
        }.getOrElse { return@withContext ToolResult("Invalid regex: ${it.message}", true) }
        val inc = args.optString("include").takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val out = StringBuilder(); var hits = 0
        for (f in ctx.ws.walk()) {
            val rel = ctx.ws.rel(f)
            if (inc != null && !inc.matches(rel)) continue
            if (f.length() > MAX_READ_BYTES || isBinary(f)) continue
            f.useLines { seq ->
                seq.forEachIndexed { i, line ->
                    if (hits < 200 && rx.containsMatchIn(line)) { out.append(rel).append(':').append(i + 1).append(": ").append(line.trim().take(240)).append('\n'); hits++ }
                }
            }
            if (hits >= 200) { out.append("… more results truncated\n"); break }
        }
        ToolResult(if (hits == 0) "No matches." else out.toString())
    }
}

fun isBinary(f: java.io.File): Boolean = runCatching {
    f.inputStream().use { s -> val b = ByteArray(1024); val n = s.read(b); (0 until maxOf(n, 0)).any { b[it] == 0.toByte() } }
}.getOrDefault(true)

fun humanSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format("%.1f MB", n / 1048576.0)
}

/** Minimal line diff (LCS) rendered as unified-style lines, capped for display. */
fun unifiedDiff(a: String, b: String, context: Int = 2, maxLines: Int = 120): String {
    val x = a.lines(); val y = b.lines()
    if (x.size * y.size > 4_000_000) return "(file too large to diff; ${x.size} → ${y.size} lines)"
    val dp = Array(x.size + 1) { IntArray(y.size + 1) }
    for (i in x.indices.reversed()) for (j in y.indices.reversed())
        dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val ops = ArrayList<Pair<Char, String>>()
    var i = 0; var j = 0
    while (i < x.size && j < y.size) {
        when {
            x[i] == y[j] -> { ops += ' ' to x[i]; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops += '-' to x[i]; i++ }
            else -> { ops += '+' to y[j]; j++ }
        }
    }
    while (i < x.size) ops += '-' to x[i++]
    while (j < y.size) ops += '+' to y[j++]
    val keep = BooleanArray(ops.size)
    ops.forEachIndexed { k, op -> if (op.first != ' ') for (t in maxOf(0, k - context)..minOf(ops.size - 1, k + context)) keep[t] = true }
    val out = StringBuilder(); var lines = 0; var gap = false
    ops.forEachIndexed { k, (c, s) ->
        if (!keep[k]) { gap = true; return@forEachIndexed }
        if (gap) { out.append("@@\n"); gap = false }
        if (lines++ < maxLines) out.append(c).append(' ').append(s).append('\n')
    }
    if (lines > maxLines) out.append("… ${lines - maxLines} more diff lines\n")
    return out.toString().ifEmpty { "(no changes)" }
}

fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var n = 0; var i = text.indexOf(needle)
    while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
    return n
}
