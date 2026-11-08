package com.inyogeshwar.agentmt.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import kotlinx.coroutines.delay

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private fun parse(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r", "").lines()
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") || t.startsWith("~~~") -> {
                flush()
                val fence = t.take(3); val lang = t.drop(3).trim()
                val code = StringBuilder(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            t.startsWith("#") && t.takeWhile { it == '#' }.length in 1..6 && t.getOrNull(t.takeWhile { it == '#' }.length) == ' ' -> {
                flush(); val lv = t.takeWhile { it == '#' }.length
                out += MdBlock.Heading(lv, t.drop(lv).trim().trimEnd('#').trim())
            }
            t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += MdBlock.Rule }
            Regex("^([-*+]|\\d+[.)])\\s+.*").matches(t) -> {
                flush()
                val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").find(t)!!
                val marker = m.groupValues[1].let { if (it in listOf("-", "*", "+")) "•" else it }
                out += MdBlock.ListItem(marker, m.groupValues[2], (line.length - t.length) / 2)
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
            t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-+.*")) -> {
                flush()
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val r = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!r.all { it.matches(Regex("^:?-+:?$")) }) rows += r
                    i++
                }
                out += MdBlock.Table(rows); continue
            }
            t.isEmpty() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line.trim()) }
        }
        i++
    }
    flush()
    return out
}

@Composable
fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val link = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, link) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '`' -> {
                        val e = text.indexOf('`', i + 1)
                        if (e > i) { withStyle(SpanStyle(fontFamily = Mono, background = codeBg, fontSize = 14.sp)) { append(" ${text.substring(i + 1, e)} ") }; i = e + 1 }
                        else { append(c); i++ }
                    }
                    text.startsWith("**", i) || text.startsWith("__", i) -> {
                        val d = text.substring(i, i + 2); val e = text.indexOf(d, i + 2)
                        if (e > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlinePlain(text.substring(i + 2, e))) }; i = e + 2 }
                        else { append(d); i += 2 }
                    }
                    (c == '*' || c == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                        val e = text.indexOf(c, i + 1)
                        if (e > i + 1 && (c == '*' || (i == 0 || !text[i - 1].isLetterOrDigit()))) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, e)) }; i = e + 1
                        } else { append(c); i++ }
                    }
                    text.startsWith("~~", i) -> {
                        val e = text.indexOf("~~", i + 2)
                        if (e > 0) { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, e)) }; i = e + 2 } else { append(c); i++ }
                    }
                    c == '[' -> {
                        val m = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(text.substring(i))
                        if (m != null) {
                            withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                                append(m.groupValues[1])
                            }
                            i += m.value.length
                        } else { append(c); i++ }
                    }
                    else -> { append(c); i++ }
                }
            }
        }
    }
}

private fun inlinePlain(s: String) = s.replace("`", "")

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { parse(text) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> Text(inline(b.text), color = color, style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium
                    }, modifier = Modifier.padding(top = 4.dp))
                    is MdBlock.Para -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, color = color)
                    is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                        Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(if (b.marker == "•") 18.dp else 26.dp))
                        Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, color = color)
                    }
                    is MdBlock.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)).padding(vertical = 2.dp)) {}
                        Spacer(Modifier.width(12.dp))
                        Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is MdBlock.Code -> CodeBlock(b.code, b.lang)
                    is MdBlock.Table -> Box(Modifier.horizontalScroll(rememberScrollState())
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                        Column {
                            b.rows.forEachIndexed { r, row ->
                                Row(Modifier.background(if (r == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                                    row.forEach { cell ->
                                        Text(inline(cell), style = MaterialTheme.typography.bodyMedium, fontWeight = if (r == 0) FontWeight.SemiBold else null,
                                            modifier = Modifier.width(140.dp).padding(10.dp))
                                    }
                                }
                                if (r < b.rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String = "", modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val extra = LocalExtra.current
    Column(modifier.fillMaxWidth().background(extra.code, RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = extra.subtle, modifier = Modifier.weight(1f))
            TextButton(onClick = { clip.setText(AnnotatedString(code)); copied = true }) {
                Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null, Modifier.size(15.dp), tint = extra.subtle)
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium, color = extra.subtle)
            }
        }
        Text(
            highlight(code, extra.codeText),
            fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            softWrap = false,
        )
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "private",
    "public", "protected", "internal", "override", "suspend", "data", "sealed", "enum", "const", "function", "let", "const", "def", "from",
    "async", "await", "export", "default", "new", "this", "true", "false", "null", "None", "True", "False", "try", "catch", "finally",
    "throw", "static", "void", "int", "string", "boolean", "type", "extends", "implements", "lateinit", "in", "is", "as", "elif", "lambda", "with",
)

private fun highlight(code: String, base: Color): AnnotatedString = buildAnnotatedString {
    val kw = Color(0xFFC792EA); val str = Color(0xFFA5D6A7); val num = Color(0xFFF78C6C); val com = Color(0xFF7F8796)
    val rx = Regex("(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|\\b(\\d+(?:\\.\\d+)?)\\b|\\b([A-Za-z_]+)\\b")
    var last = 0
    if (code.length > 20_000) { withStyle(SpanStyle(color = base)) { append(code) }; return@buildAnnotatedString }
    for (m in rx.findAll(code)) {
        withStyle(SpanStyle(color = base)) { append(code.substring(last, m.range.first)) }
        val (c, s, n, w) = m.destructured
        when {
            c.isNotEmpty() -> withStyle(SpanStyle(color = com, fontStyle = FontStyle.Italic)) { append(c) }
            s.isNotEmpty() -> withStyle(SpanStyle(color = str)) { append(s) }
            n.isNotEmpty() -> withStyle(SpanStyle(color = num)) { append(n) }
            w in KEYWORDS -> withStyle(SpanStyle(color = kw)) { append(w) }
            else -> withStyle(SpanStyle(color = base)) { append(w) }
        }
        last = m.range.last + 1
    }
    withStyle(SpanStyle(color = base)) { append(code.substring(last)) }
}
