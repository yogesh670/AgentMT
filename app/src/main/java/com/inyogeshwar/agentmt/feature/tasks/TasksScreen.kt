package com.inyogeshwar.agentmt.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import com.inyogeshwar.agentmt.core.ui.TagChip
import com.inyogeshwar.agentmt.core.ui.relativeTime
import com.inyogeshwar.agentmt.feature.chat.TASK_STATUSES
import com.inyogeshwar.agentmt.feature.chat.parseTodos
import com.inyogeshwar.agentmt.feature.chat.statusTint

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(c: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val sessions by remember { c.db.sessions().observeAll() }.collectAsState(initial = null)
    val links by remember { c.db.tags().observeLinks() }.collectAsState(initial = emptyList())
    val live by c.engine.live.collectAsState()
    var tag by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val tagsBySession = remember(links) { links.filter { it.kind == "session" }.groupBy({ it.ref }, { it.tag }) }
    val tasks = sessions?.filter { it.kind == "task" }
    AppScaffold("Tasks", onBack, floating = {
        ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New task") })
    }) { pad ->
        when {
            tasks == null -> Unit
            tasks.isEmpty() -> EmptyState(Icons.Outlined.Checklist, "No tasks yet",
                "A task is a focused workspace: a goal, instructions, tags and a live checklist the agent keeps while it works.", Modifier.padding(pad))
            else -> {
                val allTags = tagsBySession.filterKeys { k -> tasks.any { it.id == k } }.values.flatten().distinct().sorted()
                val shown = tasks.filter { t ->
                    (tag == null || tag in tagsBySession[t.id].orEmpty()) &&
                        (q.isBlank() || t.title.contains(q, true) || t.instructions.contains(q, true) || tagsBySession[t.id].orEmpty().any { it.contains(q.removePrefix("#"), true) })
                }
                LazyColumn(contentPadding = pad) {
                    item {
                        OutlinedTextField(q, { q = it }, placeholder = { Text("Search tasks or #tag") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp))
                    }
                    if (allTags.isNotEmpty()) item {
                        LazyRow(Modifier.padding(vertical = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { FilterChip(tag == null, { tag = null }, label = { Text("All") }) }
                            items(allTags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, label = { Text("#$t") }) }
                        }
                    }
                    TASK_STATUSES.sortedBy { listOf("active", "blocked", "todo", "done").indexOf(it.first) }.forEach { (status, label) ->
                        val group = shown.filter { it.status == status }
                        if (group.isEmpty()) return@forEach
                        item(key = "h$status") { SectionLabel("$label · ${group.size}") }
                        items(group, key = { it.id }) { t ->
                            val todos = parseTodos(t.todosJson)
                            val running = live[t.id]?.running == true
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpen(t.id) }.padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusTint(t.status)))
                                    Spacer(Modifier.width(8.dp))
                                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.6.dp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(listOfNotNull(t.projectName, if (todos.isNotEmpty()) "${todos.count { it.status == "done" }}/${todos.size} steps" else null, relativeTime(t.updatedAt)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val tt = tagsBySession[t.id].orEmpty()
                                if (tt.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tt.forEach { TagChip(it) } }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}
