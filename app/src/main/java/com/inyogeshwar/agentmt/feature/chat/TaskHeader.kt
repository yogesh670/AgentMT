package com.inyogeshwar.agentmt.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.TagChip
import com.inyogeshwar.agentmt.core.ui.TagEditor
import com.inyogeshwar.agentmt.data.db.SessionRow
import org.json.JSONArray

data class Todo(val content: String, val status: String)

fun parseTodos(json: String): List<Todo> = runCatching {
    val a = JSONArray(json); (0 until a.length()).map { a.getJSONObject(it) }.map { Todo(it.optString("content"), it.optString("status")) }
}.getOrDefault(emptyList())

val TASK_STATUSES = listOf("todo" to "To do", "active" to "In progress", "blocked" to "Blocked", "done" to "Done")

@Composable
fun statusTint(status: String) = when (status) {
    "done" -> LocalExtra.current.success; "blocked" -> LocalExtra.current.danger; "todo" -> LocalExtra.current.subtle; else -> MaterialTheme.colorScheme.secondary
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskHeader(
    s: SessionRow, tags: List<String>, knownTags: List<String>,
    onStatus: (String) -> Unit, onInstructions: (String) -> Unit, onAddTag: (String) -> Unit, onRemoveTag: (String) -> Unit,
    skills: List<String>, onSkills: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var tagEdit by remember { mutableStateOf(false) }
    var skillEdit by remember { mutableStateOf(false) }
    val todos = remember(s.todosJson) { parseTodos(s.todosJson) }
    val done = todos.count { it.status == "done" }
    val extra = LocalExtra.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).animateContentSize(),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box {
                Row(Modifier.clip(RoundedCornerShape(50)).background(statusTint(s.status).copy(alpha = 0.15f)).clickable { statusMenu = true }
                    .padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.size(7.dp).clip(CircleShape).background(statusTint(s.status)))
                    Spacer(Modifier.width(6.dp))
                    Text(TASK_STATUSES.firstOrNull { it.first == s.status }?.second ?: s.status, style = MaterialTheme.typography.labelMedium, color = statusTint(s.status))
                }
                DropdownMenu(statusMenu, { statusMenu = false }) {
                    TASK_STATUSES.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, onClick = { statusMenu = false; onStatus(k) }) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(if (todos.isEmpty()) "No checklist yet" else "$done of ${todos.size} steps", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            tags.take(2).forEach { TagChip(it, Modifier.padding(start = 4.dp)) }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (todos.isNotEmpty()) LinearProgressIndicator({ done.toFloat() / todos.size }, Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(3.dp),
            color = extra.success, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        if (open) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            todos.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (t.status) { "done" -> Icons.Filled.CheckCircle; "in_progress" -> Icons.Outlined.Timelapse; else -> Icons.Outlined.RadioButtonUnchecked },
                        null, Modifier.size(16.dp), tint = when (t.status) { "done" -> extra.success; "in_progress" -> MaterialTheme.colorScheme.secondary; else -> extra.subtle })
                    Spacer(Modifier.width(8.dp))
                    Text(t.content, style = MaterialTheme.typography.bodyMedium, textDecoration = if (t.status == "done") TextDecoration.LineThrough else null,
                        color = if (t.status == "done") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            Text("Instructions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.instructions.ifBlank { "None — tap to add constraints the agent must follow for this task." }, style = MaterialTheme.typography.bodyMedium,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { editing = s.instructions })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { TagChip(it) }
                TextButton(onClick = { tagEdit = true }) { Text(if (tags.isEmpty()) "Add tags" else "Edit tags") }
                TextButton(onClick = { skillEdit = true }) { Text(if (s.skills.isBlank()) "Skills: all" else "Skills: ${s.skills.split(',').size}") }
            }
        }
    }
    editing?.let { t ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Task instructions") },
            text = { OutlinedTextField(t, { editing = it }, minLines = 4, modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. Use plain HTML/CSS, no frameworks. Keep files under /site.") }) },
            confirmButton = { TextButton(onClick = { onInstructions(t); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    if (tagEdit) TagEditor(tags, knownTags, onAdd = onAddTag, onRemove = onRemoveTag) { tagEdit = false }
    if (skillEdit) {
        var sel by remember { mutableStateOf(s.skills.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()) }
        AlertDialog(onDismissRequest = { skillEdit = false }, title = { Text("Skills for this task") },
            text = {
                Column {
                    Text("None selected = every enabled skill is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        skills.forEach { n -> FilterChip(n in sel, { sel = if (n in sel) sel - n else sel + n }, label = { Text(n) }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSkills(sel.joinToString(",")); skillEdit = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { skillEdit = false }) { Text("Cancel") } })
    }
}
