package com.inyogeshwar.agentmt.feature.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.SectionLabel
import com.inyogeshwar.agentmt.core.ui.duration
import com.inyogeshwar.agentmt.feature.chat.AssistantBlock
import com.inyogeshwar.agentmt.feature.chat.ChatItem
import com.inyogeshwar.agentmt.feature.chat.ChatViewModel
import com.inyogeshwar.agentmt.feature.chat.ToolStepRow
import com.inyogeshwar.agentmt.feature.chat.UserBubble
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RunDetailScreen(c: AppContainer, runId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val run by remember(runId) { c.db.runs().observe(runId) }.collectAsState(initial = null)
    val msgs by remember(runId) { c.db.messages().observeRun(runId) }.collectAsState(initial = emptyList())
    val items = remember(msgs) { ChatViewModel.toItems(msgs) }
    val r = run
    AppScaffold("Run", onBack) { pad ->
        if (r == null) return@AppScaffold
        val active = r.status == "running" || r.status == "waiting"
        LazyColumn(contentPadding = pad) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(statusLabel(r.status), statusColor(r.status))
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.startedAt)) +
                            (r.endedAt?.let { " · " + duration(it - r.startedAt) } ?: ""), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Stat("Steps", r.steps.toString()); Stat("Tools", r.toolCalls.toString())
                            Stat("Tokens in", compact(r.inputTokens)); Stat("Tokens out", compact(r.outputTokens))
                        }
                        r.model?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        r.error?.let { Text(it, color = LocalExtra.current.danger, style = MaterialTheme.typography.bodyMedium) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { onOpenChat(r.sessionId) }) { Text("Open chat") }
                            if (active) Button(onClick = { c.engine.stop(r.sessionId) },
                                colors = ButtonDefaults.buttonColors(containerColor = LocalExtra.current.danger)) { Text("Stop run") }
                        }
                    }
                }
            }
            val files = r.changedFiles.lines().filter { it.isNotBlank() }
            if (files.isNotEmpty()) {
                item { SectionLabel("Changed files (${files.size})") }
                items(files) { f -> ListRow(f, null, Icons.Outlined.Description) }
            }
            if (r.routeLog.isNotBlank()) {
                item { SectionLabel("Routing") }
                item {
                    Text(r.routeLog.trim(), fontFamily = com.inyogeshwar.agentmt.app.theme.Mono, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            item { SectionLabel("Timeline") }
            items(items, key = { it.key }) { when (it) {
                is ChatItem.User -> UserBubble(it.text, it.attachments)
                is ChatItem.Assistant -> AssistantBlock(it.text, showAvatar = true, routeNote = it.routeNote)
                is ChatItem.ToolStep -> ToolStepRow(it, active)
                is ChatItem.Compacted -> com.inyogeshwar.agentmt.feature.chat.CompactedRow(it.summary)
            } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compact(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1000 -> "%.1fk".format(n / 1e3); else -> n.toString() }
