package com.inyogeshwar.agentmt.feature.chat

import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.inyogeshwar.agentmt.agent.attachments.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.inyogeshwar.agentmt.agent.ApprovalAnswer
import com.inyogeshwar.agentmt.agent.PendingApproval
import com.inyogeshwar.agentmt.agent.PendingQuestion
import com.inyogeshwar.agentmt.agent.tools.ToolKind
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.Logo
import com.inyogeshwar.agentmt.core.ui.Markdown
import org.json.JSONObject

@Composable
fun UserBubble(text: String, attachments: List<Attachment> = emptyList()) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
        attachments.forEach { a ->
            if (a.kind == "image") AttachmentThumb(a) else Row(
                Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(attachmentIcon(a.kind), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.kind.uppercase()} · ${com.inyogeshwar.agentmt.agent.tools.humanSize(a.size)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (text.isNotBlank()) SelectionContainer {
            Text(
                text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(LocalExtra.current.userBubble).padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun AttachmentThumb(a: Attachment) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = java.io.File(ctx.filesDir, a.path)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 600) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let { Image(it, a.name, contentScale = ContentScale.Crop, modifier = Modifier.padding(bottom = 6.dp).size(180.dp).clip(RoundedCornerShape(16.dp))) }
}

fun attachmentIcon(kind: String): ImageVector = when (kind) {
    "image" -> Icons.Outlined.Image
    "pdf" -> Icons.Outlined.PictureAsPdf
    "text" -> Icons.Outlined.Description
    else -> Icons.Outlined.AttachFile
}

@Composable
fun AssistantBlock(text: String, showAvatar: Boolean, streaming: Boolean = false, routeNote: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Box(Modifier.width(30.dp)) { if (showAvatar) Logo(22.dp, Modifier.padding(top = 2.dp)) }
        Column(Modifier.weight(1f)) {
            Markdown(if (streaming) "$text ▍" else text)
            routeNote?.let {
                Text(it, fontFamily = Mono, fontSize = 10.5.sp, color = LocalExtra.current.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

fun toolIcon(name: String): ImageVector = when {
    name.startsWith("mcp__") -> Icons.Outlined.Extension
    name == "read_file" -> Icons.Outlined.Description
    name == "write_file" -> Icons.AutoMirrored.Outlined.NoteAdd
    name == "edit_file" -> Icons.Outlined.EditNote
    name == "run_command" -> Icons.Outlined.Terminal
    name == "list_dir" -> Icons.Outlined.FolderOpen
    name == "glob" || name == "grep" -> Icons.Outlined.Search
    name == "web_fetch" -> Icons.Outlined.Language
    name == "load_skill" -> Icons.Outlined.AutoStories
    name == "update_todos" -> Icons.Outlined.Checklist
    else -> Icons.Outlined.Extension
}

fun toolLabel(name: String, args: String): String {
    val a = runCatching { JSONObject(args) }.getOrDefault(JSONObject())
    return when (name) {
        "read_file" -> "Read ${a.optString("path")}"
        "write_file" -> "Write ${a.optString("path")}"
        "edit_file" -> "Edit ${a.optString("path")}"
        "list_dir" -> "List ${a.optString("path", ".").ifEmpty { "." }}"
        "glob" -> "Find ${a.optString("pattern")}"
        "grep" -> "Search “${a.optString("pattern")}”"
        "run_command" -> "$ ${a.optString("command")}"
        "web_fetch" -> "Fetch ${a.optString("url")}"
        "load_skill" -> "Use skill ${a.optString("name")}"
        "update_todos" -> a.optJSONArray("todos")?.let { t -> "Checklist ${(0 until t.length()).count { t.optJSONObject(it)?.optString("status") == "done" }}/${t.length()}" } ?: "Checklist"
        else -> if (name.startsWith("mcp__")) name.split("__").drop(1).joinToString(" · ") else name
    }
}

@Composable
fun ToolStepRow(item: ChatItem.ToolStep, running: Boolean) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val extra = LocalExtra.current
    Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 16.dp, top = 2.dp, bottom = 2.dp).animateContentSize()) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(toolIcon(item.name), null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(toolLabel(item.name, item.args), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                fontFamily = if (item.name == "run_command") Mono else null)
            Spacer(Modifier.width(8.dp))
            when {
                item.status == null && running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                item.status == "ok" -> Icon(Icons.Filled.CheckCircle, "Done", Modifier.size(14.dp), tint = extra.success)
                item.status == "denied" -> Icon(Icons.Filled.Block, "Denied", Modifier.size(14.dp), tint = extra.warning)
                item.status == "error" -> Icon(Icons.Filled.ErrorOutline, "Failed", Modifier.size(14.dp), tint = extra.danger)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = extra.subtle)
        }
        if (open) {
            val body = buildString {
                val pretty = runCatching { JSONObject(item.args).toString(2) }.getOrDefault(item.args)
                if (pretty.length > 2) append(pretty.take(4000)).append("\n\n")
                append(item.output?.take(6000) ?: "…")
            }
            Text(
                body, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = extra.codeText,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(extra.code)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                softWrap = false,
            )
        }
    }
}

@Composable
fun LiveStatus(text: String, icon: ImageVector = Icons.Outlined.Psychology) {
    Row(Modifier.padding(start = 50.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ReasoningBox(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 46.dp, end = 16.dp, top = 4.dp).animateContentSize()) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Thinking", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Text(text.takeLast(1500), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 26.dp, top = 4.dp))
    }
}

@Composable
fun ApprovalCard(a: PendingApproval, onAnswer: (ApprovalAnswer) -> Unit) {
    val extra = LocalExtra.current
    val verb = when (a.kind) {
        ToolKind.Write -> "Allow this change?"
        ToolKind.Execute -> "Run this command?"
        ToolKind.Network -> "Allow network access?"
        ToolKind.Read -> "Allow reading?"
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, extra.warning.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(extra.warning.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(17.dp), tint = extra.warning)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(verb, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(toolLabel(a.toolName, "{}").takeIf { a.toolName.startsWith("mcp__") } ?: a.summary,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        a.preview?.takeIf { it.isNotBlank() }?.let { pv ->
            Spacer(Modifier.size(12.dp))
            Text(
                diffColored(pv), fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, softWrap = false,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(extra.code)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onAnswer(ApprovalAnswer.Deny) }, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = { onAnswer(ApprovalAnswer.Once) }, modifier = Modifier.weight(1f)) { Text("Allow") }
        }
        TextButton(onClick = { onAnswer(ApprovalAnswer.Always) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Always allow ${if (a.kind == ToolKind.Execute) "this command" else "this"}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun QuestionCard(q: PendingQuestion, onAnswer: (String) -> Unit) {
    var text by rememberSaveable(q.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Text("The agent needs your input", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(10.dp))
        SelectionContainer { Text(q.question, style = MaterialTheme.typography.bodyLarge) }
        if (q.options.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEach { option ->
                    OutlinedButton(onClick = { onAnswer(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type your own answer") }, maxLines = 4, shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer("(skipped)") }) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAnswer(text) }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
fun diffColored(text: String): AnnotatedString {
    val extra = LocalExtra.current
    return buildAnnotatedString {
        text.lines().forEachIndexed { i, l ->
            val col = when {
                l.startsWith("+") -> extra.success
                l.startsWith("-") -> extra.danger
                l.startsWith("@@") -> extra.subtle
                else -> extra.codeText
            }
            withStyle(SpanStyle(color = col)) { append(l) }
            if (i < text.lines().size - 1) append('\n')
        }
    }
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit, onFix: (() -> Unit)?, onContinue: (() -> Unit)? = null) {
    val extra = LocalExtra.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(extra.danger.copy(alpha = 0.10f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.ErrorOutline, null, tint = extra.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            if (onFix != null) TextButton(onClick = onFix) { Text("Open providers") }
            if (onContinue != null) {
                TextButton(onClick = onRetry) { Text("Restart") }
                Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = extra.danger.copy(alpha = 0.85f))) { Text("Continue") }
            } else Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = extra.danger.copy(alpha = 0.85f))) { Text("Retry") }
        }
    }
}

@Composable
fun SuggestionCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(min = 150.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}
