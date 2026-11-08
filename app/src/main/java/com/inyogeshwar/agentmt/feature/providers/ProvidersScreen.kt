package com.inyogeshwar.agentmt.feature.providers

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.ProviderPresets
import com.inyogeshwar.agentmt.agent.modelList
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.SectionLabel

@Composable
fun ProvidersScreen(c: AppContainer, onBack: () -> Unit, onEdit: (id: String?, preset: String?) -> Unit, onRouting: () -> Unit = {}) {
    val providers by remember { c.db.providers().observeAll() }.collectAsState(initial = emptyList())
    val creds by remember { c.db.credentials().observeAll() }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    val extra = LocalExtra.current
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    AppScaffold("Providers", onBack) { pad ->
        LazyColumn(contentPadding = pad) {
            item { ListRow("Routing & fallback", "Profiles, key rotation, usage and health", Icons.Outlined.SwapHoriz, iconTint = MaterialTheme.colorScheme.secondary, onClick = onRouting) }
            if (providers.isNotEmpty()) item { SectionLabel("Connected") }
            items(providers, key = { it.id }) { p ->
                val keys = creds.filter { it.providerId == p.id }
                val now = System.currentTimeMillis()
                val ready = keys.count { it.enabled && it.status != "invalid" && it.cooldownUntil <= now }
                val n = p.modelList().size
                ListRow(
                    p.name,
                    listOfNotNull(
                        if (keys.isEmpty()) (if (ProviderPresets.byId(p.id)?.needsKey == false) "No key needed" else "No key") else "${keys.size} key${if (keys.size == 1) "" else "s"}",
                        "$n model${if (n == 1) "" else "s"}", if (!p.enabled) "Disabled" else null,
                    ).joinToString(" · "),
                    if (p.kind == "anthropic") Icons.Outlined.Hub else if (p.baseUrl.contains("127.0.0.1") || p.baseUrl.contains("localhost") || p.baseUrl.contains("192.168")) Icons.Outlined.Computer else Icons.Outlined.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = {
                        when {
                            keys.isNotEmpty() && ready == 0 -> Pill(if (keys.any { it.status == "invalid" }) "Check key" else "Cooling down", extra.warning)
                            settings.defaultProviderId == p.id -> Pill("Default", MaterialTheme.colorScheme.secondary)
                            keys.isNotEmpty() -> Pill("$ready ready", extra.success)
                        }
                    },
                    onClick = { onEdit(p.id, null) },
                )
            }
            val avail = ProviderPresets.all.filter { pr -> providers.none { it.id == pr.id } || pr.id == "custom" }
            item { SectionLabel("Add a provider") }
            item {
                androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("Search ${avail.size} providers") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) avail.filter { it.featured } else avail.filter { it.name.lowercase().contains(q) || it.id.contains(q) || it.baseUrl.contains(q) || it.note.lowercase().contains(q) || it.suggestedModels.any { m -> m.lowercase().contains(q) } }
            items(shown, key = { "preset" + it.id }) { pr ->
                ListRow(pr.name, pr.note.ifBlank { pr.baseUrl.removePrefix("https://") }, Icons.Outlined.AddCircleOutline, onClick = { onEdit(null, pr.id) })
            }
            if (q.isEmpty()) item {
                Text("${avail.count { !it.featured }} more in the catalog — search by name or model (e.g. \"kimi\", \"free\").",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
