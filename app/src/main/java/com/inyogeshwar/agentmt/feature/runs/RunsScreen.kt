package com.inyogeshwar.agentmt.feature.runs

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.duration
import com.inyogeshwar.agentmt.core.ui.relativeTime
import com.inyogeshwar.agentmt.data.db.RunEntity
import kotlinx.coroutines.launch

@Composable
fun statusColor(s: String): Color {
    val e = LocalExtra.current
    return when (s) { "succeeded" -> e.success; "failed" -> e.danger; "waiting" -> e.warning; "running" -> MaterialTheme.colorScheme.secondary; else -> e.subtle }
}

fun statusLabel(s: String) = when (s) { "succeeded" -> "Done"; "failed" -> "Failed"; "waiting" -> "Needs approval"; "running" -> "Running"; "cancelled" -> "Stopped"; else -> s }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunsScreen(c: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val runs by c.db.runs().observeAll().collectAsState(initial = null)
    val projects by c.db.projects().observeAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppScaffold("Runs", onBack, actions = {
        IconButton(onClick = { confirmClear = true }, enabled = runs?.any { it.status !in listOf("running", "waiting") } == true) { Icon(Icons.Outlined.ClearAll, "Clear history") }
    }) { pad ->
        val list = runs
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(Icons.Outlined.History, "No runs yet", "Every task you give the agent shows up here with its steps, tool calls and changed files.", Modifier.padding(pad))
            else -> LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All", "active" to "Active", "succeeded" to "Done", "failed" to "Failed").forEach { (k, l) ->
                            FilterChip(filter == k, { filter = k }, label = { Text(l) })
                        }
                    }
                }
                val shown = list.filter {
                    when (filter) { "active" -> it.status == "running" || it.status == "waiting"; "all" -> true; else -> it.status == filter }
                }
                items(shown, key = { it.id }) { r -> RunCard(r, projects.firstOrNull { it.id == r.projectId }?.name, { onOpen(r.id) }) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (confirmClear) ConfirmDialog("Clear run history?", "Finished runs are removed from this list. Chats and files are kept.", "Clear", true,
        onConfirm = { scope.launch { c.db.runs().clearFinished() } }, onDismiss = { confirmClear = false })
}

@Composable
private fun RunCard(r: RunEntity, project: String?, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.status == "running") { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp); Spacer(Modifier.width(8.dp)) }
                Pill(statusLabel(r.status), statusColor(r.status))
                Spacer(Modifier.weight(1f))
                Text(relativeTime(r.startedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val files = r.changedFiles.lines().count { it.isNotBlank() }
            Text(
                listOfNotNull(project, "${r.steps} steps", "${r.toolCalls} tools", if (files > 0) "$files files changed" else null,
                    r.endedAt?.let { duration(it - r.startedAt) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
