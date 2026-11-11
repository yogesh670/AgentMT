package com.inyogeshwar.agentmt.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Applies one compact, multi-file patch. The format is deliberately small and file-oriented:
 *
 * *** Begin Patch
 * *** Add File: hello.txt
 * +Hello world
 * *** Update File: src/app.py
 * *** Move to: src/main.py
 * @@ def greet():
 * -print("Hi")
 * +print("Hello, world!")
 * *** Delete File: obsolete.txt
 * *** End Patch
 *
 * Every hunk is matched against the file's current text before anything is written, so a bad
 * hunk fails the whole patch instead of leaving a half-applied edit.
 */
class PatchTool : Tool {
    override val name = "patch"
    override val kind = ToolKind.Write
    override val description = """
Edit one or more files with a single patch. The patch language is a small, file-oriented diff:

*** Begin Patch
*** Add File: path
+every line of the new file starts with +
*** Update File: path
*** Move to: optional/new/path
@@ optional context line, such as a function signature
 unchanged line (starts with a space)
-removed line
+added line
*** Delete File: path
*** End Patch

Each Update hunk starts with @@ and should include about 3 unchanged lines before and after the
change so it can be found uniquely. Use patch for multi-file changes or several edits in one file;
use edit_file for a single small replacement. All hunks are checked first, and nothing is written
if any hunk cannot be applied.
""".trim()
    override val schema = schema(
        p("patchText", "string", "The complete patch text, starting with *** Begin Patch and ending with *** End Patch"),
        required = listOf("patchText"),
    )

    override fun target(args: JSONObject): String =
        runCatching { Patch.parse(args.optString("patchText")).firstOrNull()?.path }.getOrNull() ?: "patch"

    override fun summary(args: JSONObject) = "Applied patch"

    override fun preview(args: JSONObject, ctx: ToolContext): String =
        args.optString("patchText").take(6000)

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val text = args.optString("patchText")
        if (text.isBlank()) return@withContext ToolResult("patchText is required", true)
        val ops = try {
            Patch.parse(text)
        } catch (e: ToolError) {
            return@withContext ToolResult(e.message ?: "Invalid patch", true)
        }

        val work = LinkedHashMap<String, String?>()
        val changes = ArrayList<Patch.Change>()
        try {
            for (op in ops) {
                val rel = ctx.ws.rel(ctx.ws.resolve(op.path))
                val before = if (work.containsKey(rel)) work[rel] else ctx.ws.resolve(rel).takeIf { it.isFile }?.readText()
                when (op) {
                    is Patch.Op.Add -> {
                        if (before != null) throw ToolError("Add File: $rel already exists.")
                        val content = if (op.lines.isEmpty()) "" else op.lines.joinToString("\n") + "\n"
                        work[rel] = content
                        changes += Patch.Change(rel, 'A', op.lines.size, 0)
                    }
                    is Patch.Op.Delete -> {
                        if (before == null) throw ToolError("Delete File: $rel does not exist.")
                        work[rel] = null
                        changes += Patch.Change(rel, 'D', 0, lineCount(before))
                    }
                    is Patch.Op.Update -> {
                        if (before == null) throw ToolError("Update File: $rel does not exist.")
                        val after = Patch.applyUpdate(rel, before, op.hunks)
                        val target = op.moveTo?.let { ctx.ws.rel(ctx.ws.resolve(it)) } ?: rel
                        if (target != rel && (work.containsKey(target) || ctx.ws.resolve(target).exists()))
                            throw ToolError("Move to: $target already exists.")
                        work[rel] = null
                        work[target] = after
                        val (plus, minus) = Patch.changeStats(op.hunks)
                        changes += Patch.Change(target, 'M', plus, minus)
                    }
                }
            }
        } catch (e: ToolError) {
            return@withContext ToolResult(e.message ?: "Invalid patch", true)
        }

        for ((rel, content) in work) {
            val f = ctx.ws.resolve(rel)
            if (content == null) {
                if (f.exists()) f.delete()
            } else {
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
        }

        val summary = buildString {
            append("Applied patch:\n")
            changes.forEach { c ->
                append(c.kind).append(' ').append(c.path)
                if (c.kind != 'D') append(" (+").append(c.plus).append(" -").append(c.minus).append(')')
                append('\n')
            }
        }
        ToolResult(summary.trimEnd(), changedPath = changes.firstOrNull { it.kind != 'D' }?.path ?: changes.firstOrNull()?.path)
    }
}

private fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.lines().size - if (text.endsWith("\n")) 1 else 0

/** Parser and in-memory applier for the patch format. Kept separate so it can be tested directly. */
internal object Patch {
    data class Hunk(val header: String?, val lines: List<String>)

    sealed class Op(val path: String) {
        class Add(path: String, val lines: List<String>) : Op(path)
        class Delete(path: String) : Op(path)
        class Update(path: String, val moveTo: String?, val hunks: List<Hunk>) : Op(path)
    }

    data class Change(val path: String, val kind: Char, val plus: Int, val minus: Int)

    fun parse(text: String): List<Op> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        var i = raw.indexOfFirst { it.trim() == "*** Begin Patch" }
        if (i < 0) i = raw.indexOfFirst { isFileHeader(it) }
        if (i < 0) throw ToolError("The patch must start with *** Begin Patch.")
        if (raw[i].trim() == "*** Begin Patch") i++

        val ops = ArrayList<Op>()
        while (i < raw.size) {
            val line = raw[i]
            when {
                line.trim() == "*** End Patch" -> {
                    i++
                    break
                }
                line.startsWith("*** Add File:") -> {
                    val path = pathAfter(line, "*** Add File:")
                    i++
                    val body = ArrayList<String>()
                    while (i < raw.size && !raw[i].startsWith("*** ")) {
                        val l = raw[i]
                        body += when {
                            l.startsWith("+") -> l.substring(1)
                            l.isEmpty() -> ""
                            else -> throw ToolError("Add File lines must start with '+': $l")
                        }
                        i++
                    }
                    ops += Op.Add(path, body)
                }
                line.startsWith("*** Delete File:") -> {
                    ops += Op.Delete(pathAfter(line, "*** Delete File:"))
                    i++
                }
                line.startsWith("*** Update File:") -> {
                    val path = pathAfter(line, "*** Update File:")
                    i++
                    var moveTo: String? = null
                    if (i < raw.size && raw[i].startsWith("*** Move to:")) {
                        moveTo = pathAfter(raw[i], "*** Move to:")
                        i++
                    }
                    val hunks = ArrayList<Hunk>()
                    var header: String? = null
                    var body = ArrayList<String>()
                    while (i < raw.size && !raw[i].startsWith("*** ")) {
                        val l = raw[i]
                        if (l.startsWith("@@")) {
                            if (header != null || body.isNotEmpty()) hunks += Hunk(header, body)
                            header = l.removePrefix("@@").trim().ifEmpty { null }
                            body = ArrayList()
                        } else {
                            val c = if (l.isEmpty()) ' ' else l[0]
                            if (c != ' ' && c != '+' && c != '-')
                                throw ToolError("Patch lines must start with ' ', '+' or '-': $l")
                            body += if (l.isEmpty()) " " else l
                        }
                        i++
                    }
                    if (header != null || body.isNotEmpty()) hunks += Hunk(header, body)
                    if (hunks.isEmpty()) throw ToolError("Update File: $path has no changes.")
                    ops += Op.Update(path, moveTo, hunks)
                }
                line.isBlank() -> i++
                else -> throw ToolError("Unexpected line in patch: $line")
            }
        }
        if (ops.isEmpty()) throw ToolError("The patch contains no file sections.")
        return ops
    }

    private fun isFileHeader(line: String) =
        line.startsWith("*** Add File:") || line.startsWith("*** Update File:") || line.startsWith("*** Delete File:")

    private fun pathAfter(line: String, prefix: String): String {
        val path = line.removePrefix(prefix).trim()
        if (path.isEmpty()) throw ToolError("$prefix needs a path.")
        return path
    }

    fun applyUpdate(path: String, original: String, hunks: List<Hunk>): String {
        val crlf = original.contains("\r\n")
        val hadFinalNewline = original.endsWith("\n")
        val normalized = original.replace("\r\n", "\n")
        val lines = if (normalized.isEmpty()) ArrayList() else ArrayList(normalized.removeSuffix("\n").split("\n"))
        var cursor = 0

        for (hunk in hunks) {
            val old = hunk.lines.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.substring(1) }
            val new = hunk.lines.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.substring(1) }
            if (old.isEmpty()) {
                var at = cursor
                hunk.header?.let { h ->
                    val found = (cursor until lines.size).firstOrNull { lines[it].trim() == h }
                        ?: lines.indices.firstOrNull { lines[it].trim() == h }
                    if (found != null) at = found + 1
                }
                at = at.coerceIn(0, lines.size)
                lines.addAll(at, new)
                cursor = at + new.size
            } else {
                val at = seek(lines, old, cursor)
                    ?: throw ToolError(
                        "Could not find the lines to change in $path:\n" +
                            old.take(6).joinToString("\n") { "  $it" } +
                            "\nRe-read the file and rebuild the patch from its exact current text.",
                    )
                repeat(old.size) { lines.removeAt(at) }
                lines.addAll(at, new)
                cursor = at + new.size
            }
        }

        var out = lines.joinToString("\n")
        if (hadFinalNewline || (original.isEmpty() && lines.isNotEmpty())) out += "\n"
        if (crlf) out = out.replace("\n", "\r\n")
        return out
    }

    private fun seek(lines: List<String>, old: List<String>, from: Int): Int? {
        fun find(start: Int, exact: Boolean): Int? {
            if (old.isEmpty() || start < 0 || start + old.size > lines.size) return null
            for (i in start..lines.size - old.size) {
                var ok = true
                for (j in old.indices) {
                    val a = lines[i + j]
                    val b = old[j]
                    if (if (exact) a != b else a.trimEnd() != b.trimEnd()) {
                        ok = false
                        break
                    }
                }
                if (ok) return i
            }
            return null
        }
        return find(from, true) ?: find(0, true) ?: find(from, false) ?: find(0, false)
    }

    fun changeStats(hunks: List<Hunk>): Pair<Int, Int> {
        var plus = 0
        var minus = 0
        hunks.forEach { h ->
            h.lines.forEach { l ->
                when {
                    l.startsWith("+") -> plus++
                    l.startsWith("-") -> minus++
                }
            }
        }
        return plus to minus
    }
}
