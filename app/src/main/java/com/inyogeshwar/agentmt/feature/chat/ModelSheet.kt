package com.inyogeshwar.agentmt.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    choices: List<ModelChoice>, current: ModelChoice?, onPick: (ModelChoice) -> Unit, onManage: () -> Unit,
    profiles: List<com.inyogeshwar.agentmt.data.db.RoutingProfileEntity> = emptyList(),
    currentProfile: com.inyogeshwar.agentmt.data.db.RoutingProfileEntity? = null,
    onPickProfile: (com.inyogeshwar.agentmt.data.db.RoutingProfileEntity?) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            if (choices.size > 8) OutlinedTextField(q, { q = it }, placeholder = { Text("Search models") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            val filtered = choices.filter { q.isBlank() || it.model.contains(q, true) || it.providerName.contains(q, true) }
            if (filtered.isEmpty()) Text(
                if (choices.isEmpty()) "No models yet. Add a provider and fetch its models." else "No match.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
            )
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (profiles.isNotEmpty() && q.isBlank()) {
                    item(key = "hp") { SectionLabel("Routing profiles") }
                    items(profiles, key = { "p" + it.id }) { p ->
                        val n = com.inyogeshwar.agentmt.agent.routing.parseCandidates(p.candidatesJson).size
                        ListRow(p.name, "$n candidates · ${if (p.mode == "pinned") "pinned" else "falls back in order"}", trailing = {
                            if (currentProfile?.id == p.id) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                        }, onClick = { onPickProfile(p); onDismiss() })
                    }
                }
                filtered.groupBy { it.providerName }.forEach { (provider, list) ->
                    item(key = "h$provider") { SectionLabel(provider) }
                    items(list, key = { it.providerId + it.model }) { m ->
                        ListRow(m.label, listOfNotNull(if (m.alias != null) m.model else null, if (m.vision == true) "vision" else null).joinToString(" · ").ifEmpty { null }, trailing = {
                            if (currentProfile == null && current?.providerId == m.providerId && current.model == m.model) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                        }, onClick = { onPick(m); onDismiss() })
                    }
                }
            }
            OutlinedButton(onClick = { onDismiss(); onManage() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Outlined.Tune, null); Spacer(Modifier.padding(4.dp)); Text("Manage providers")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
