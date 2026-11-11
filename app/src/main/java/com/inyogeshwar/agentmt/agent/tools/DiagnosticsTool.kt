package com.inyogeshwar.agentmt.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Quick, local sanity check after edits. It validates JSON and XML, and looks for unbalanced
 * brackets or unterminated strings in common source languages. It is not a compiler.
 */
class DiagnosticsTool : Tool {
    override val name = "diagnostics"
    override val kind = ToolKind.Read
    override val description = "Run a quick syntax check on files (JSON/XML validity and unbalanced brackets or quotes in common source " +
        "languages). Use it after larger edits to confirm nothing is broken. It is not a compiler: it cannot find type or import errors."
    override val schema: JSONObject = JSONObject(
        """{"type":"object","properties":{"paths":{"type":"array","description":"Files to check","items":{"type":"string"}}},"required":["paths"]}""",
    )

    override fun target(args: JSONObject): String {
        val a = args.optJSONArray("paths") ?: return "files"
        return (0 until minOf(a.length(), 3)).joinToString(", ") { a.optString(it) }.ifEmpty { "files" }
    }

    override fun summary(args: JSONObject): String {
        val n = args.optJSONArray("paths")?.length() ?: 0
        return "Checked $n file${if (n == 1) "" else "s"}"
    }

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val paths = args.optJSONArray("paths") ?: return@withContext ToolResult("paths is required", true)
        if (paths.length() == 0) return@withContext ToolResult("paths is empty", true)
        val out = StringBuilder()
        var problems = 0
        outer@ for (i in 0 until minOf(paths.length(), 30)) {
            val path = paths.optString(i)
            var resolveErr: Throwable? = null
            val f = runCatching { ctx.ws.resolve(path) }.onFailure { resolveErr = it }.getOrNull()
            if (f == null) {
                out.append(path).append(": ").append(resolveErr?.message ?: "invalid path").append('\n')
                continue@outer
            }
            if (!f.isFile) {
                out.append(path).append(": not found\n")
                continue@outer
            }
            if (f.length() > 2_000_000) {
                out.append(path).append(": skipped (too large)\n")
                continue@outer
            }
            val issues = runCatching { Diagnostics.check(f, f.readText()) }.getOrElse {
                listOf(Diagnostics.Issue(1, 1, it.message ?: "could not read file"))
            }
            if (issues.isEmpty()) {
                out.append("OK  ").append(path).append('\n')
            } else {
                problems += issues.size
                out.append("ISSUES  ").append(path).append('\n')
                issues.take(8).forEach { issue ->
                    out.append("  line ").append(issue.line).append(':').append(issue.col).append("  ").append(issue.message).append('\n')
                }
                if (issues.size > 8) out.append("  … ").append(issues.size - 8).append(" more\n")
            }
        }
        out.append(if (problems == 0) "No syntax problems found." else "$problems possible problem(s).")
        ToolResult(out.toString(), isError = false)
    }
}

internal object Diagnostics {
    data class Issue(val line: Int, val col: Int, val message: String)

    private val sourceExts = setOf(
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "mjs", "cjs", "c", "h", "cc", "cpp", "cxx", "hpp",
        "cs", "go", "rs", "swift", "dart", "php", "scala", "groovy", "gradle", "py", "rb", "lua", "sh", "bash",
    )

    fun check(file: File, text: String): List<Issue> {
        return when (file.extension.lowercase()) {
            "json" -> checkJson(text)
            "xml", "svg", "html", "htm", "plist" -> checkXml(text)
            in sourceExts -> checkSource(text, file.extension.lowercase())
            else -> emptyList()
        }
    }

    private fun checkJson(text: String): List<Issue> {
        return try {
            JSONTokener(text).nextValue()
            emptyList()
        } catch (e: Exception) {
            val message = e.message.orEmpty().take(240).ifEmpty { "Invalid JSON" }
            val offset = Regex("character (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val (line, col) = if (offset != null) lineCol(text, offset) else 1 to 1
            listOf(Issue(line, col, message))
        }
    }

    private fun checkXml(text: String): List<Issue> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray()))
            emptyList()
        } catch (e: SAXParseException) {
            listOf(Issue(e.lineNumber.coerceAtLeast(1), e.columnNumber.coerceAtLeast(1), e.message.orEmpty().take(240)))
        } catch (e: Exception) {
            listOf(Issue(1, 1, e.message.orEmpty().take(240).ifEmpty { "Invalid XML" }))
        }
    }

    private fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, text.length)
        var line = 1
        var col = 1
        for (i in 0 until safe) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else col++
        }
        return line to col
    }

    private fun checkSource(text: String, ext: String): List<Issue> {
        val lineComments = when (ext) {
            "py", "rb", "sh", "bash", "php" -> listOf("#")
            "lua" -> listOf("--")
            else -> listOf("//")
        }
        val blockComments = ext !in setOf("py", "rb", "sh", "bash", "lua")
        val tripleQuotes = ext in setOf("py", "kt", "kts")
        val backtickString = ext in setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")

        val issues = ArrayList<Issue>()
        val stack = ArrayList<Triple<Char, Int, Int>>()
        val n = text.length
        var i = 0
        var line = 1
        var col = 1

        fun bump() {
            if (text[i] == '\n') {
                line++
                col = 1
            } else col++
            i++
        }

        fun add(message: String, atLine: Int = line, atCol: Int = col) {
            if (issues.size < 40) issues += Issue(atLine, atCol, message)
        }

        while (i < n) {
            if (lineComments.any { text.startsWith(it, i) }) {
                while (i < n && text[i] != '\n') bump()
                continue
            }
            if (blockComments && text.startsWith("/*", i)) {
                repeat(2) { if (i < n) bump() }
                while (i < n && !text.startsWith("*/", i)) bump()
                repeat(2) { if (i < n) bump() }
                continue
            }
            if (tripleQuotes && (text.startsWith("\"\"\"", i) || text.startsWith("'''", i))) {
                val q = text.substring(i, i + 3)
                val startLine = line
                val startCol = col
                repeat(3) { if (i < n) bump() }
                var closed = false
                while (i < n) {
                    if (text.startsWith(q, i)) {
                        repeat(3) { if (i < n) bump() }
                        closed = true
                        break
                    }
                    bump()
                }
                if (!closed) add("Unterminated triple-quoted string", startLine, startCol)
                continue
            }
            val c = text[i]
            if (c == '"' || c == '\'' || (backtickString && c == '`')) {
                val quote = c
                val startLine = line
                val startCol = col
                bump()
                var closed = false
                while (i < n) {
                    val ch = text[i]
                    if (ch == '\\') {
                        bump()
                        if (i < n) bump()
                    } else if (ch == quote) {
                        bump()
                        closed = true
                        break
                    } else if (ch == '\n' && quote != '`') {
                        break
                    } else bump()
                }
                if (!closed) add("Unterminated string", startLine, startCol)
                continue
            }
            when (c) {
                '(', '[', '{' -> stack += Triple(c, line, col)
                ')', ']', '}' -> {
                    val open = when (c) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (stack.isEmpty()) add("Unmatched '$c'")
                    else if (stack.last().first != open) {
                        add("'$c' closes '${stack.last().first}'")
                        stack.removeAt(stack.size - 1)
                    } else stack.removeAt(stack.size - 1)
                }
            }
            bump()
        }
        stack.reversed().forEach { (ch, l, c) -> add("Unclosed '$ch'", l, c) }
        return issues
    }
}
