package com.inyogeshwar.agentmt.feature.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.agent.routing.dayKey
import com.inyogeshwar.agentmt.agent.routing.parseCandidates
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.core.ui.SectionLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingScreen(c: AppContainer, onBack: () -> Unit, onProfile: (String?) -> Unit) {
    val s by c.settings.state.collectAsState()
    val profiles by remember { c.db.routing().observeAll() }.collectAsState(initial = emptyList())
    val creds by remember { c.db.credentials().observeAll() }.collectAsState(initial = emptyList())
    val providers by remember { c.db.providers().observeAll() }.collectAsState(initial = emptyList())
    val usage by remember { c.db.usage().observeSince(dayKey(System.currentTimeMillis() - 6 * 86_400_000L)) }.collectAsState(initial = emptyList())
    var attempts by remember { mutableIntStateOf(s.maxAttempts) }
    val extra = LocalExtra.current
    val pName = providers.associate { it.id to it.name }
    val cName = creds.associate { it.id to "${pName[it.providerId] ?: it.providerId} · ${it.label}" }

    AppScaffold("Routing & fallback", onBack, floating = {
        ExtendedFloatingActionButton(onClick = { onProfile(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New profile") })
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                Text("When a model or key fails (rate limit, outage, bad key), AgentMT tries the next candidate you configured — never more than your retry budget, and never after the answer has started streaming.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            item { SectionLabel("Policy") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Try other keys first", style = MaterialTheme.typography.bodyLarge)
                                Text("On a rate limit, use the same provider's next key before switching model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(s.rotateKeys, { v -> c.settings.update { it.copy(rotateKeys = v) } })
                        }
                        Text("Retry budget: $attempts attempts per step", style = MaterialTheme.typography.bodyLarge)
                        Slider(attempts.toFloat(), { attempts = it.toInt() }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = { c.settings.update { it.copy(maxAttempts = attempts) } })
                        if (profiles.isNotEmpty()) {
                            Text("New chats use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(s.defaultProfileId == null, { c.settings.update { it.copy(defaultProfileId = null) } }, label = { Text("Default model") })
                                profiles.forEach { p -> FilterChip(s.defaultProfileId == p.id, { c.settings.update { it.copy(defaultProfileId = p.id) } }, label = { Text(p.name) }) }
                            }
                            Text("If a chosen model fails, fall back to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(s.fallbackProfileId == null, { c.settings.update { it.copy(fallbackProfileId = null) } }, label = { Text("Nothing") })
                                profiles.forEach { p -> FilterChip(s.fallbackProfileId == p.id, { c.settings.update { it.copy(fallbackProfileId = p.id) } }, label = { Text(p.name) }) }
                            }
                        }
                    }
                }
            }
            item { SectionLabel("Profiles") }
            if (profiles.isEmpty()) item {
                Text("No profiles yet. A profile is an ordered list like: OpenRouter Account 1 / model A → Account 2 / model A → Gemini / model B.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(profiles, key = { it.id }) { p ->
                val cands = parseCandidates(p.candidatesJson)
                ListRow(p.name, cands.joinToString(" → ") { "${pName[it.providerId] ?: "?"}/${it.model.substringAfterLast('/')}" }.ifEmpty { "Empty" },
                    Icons.Outlined.SwapHoriz, iconTint = MaterialTheme.colorScheme.secondary, onClick = { onProfile(p.id) })
            }
            item {
                Row(Modifier.padding(end = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SectionLabel("Key health", Modifier.weight(1f))
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val limited = creds.count { it.cooldownUntil > System.currentTimeMillis() || it.status == "invalid" }
                    androidx.compose.material3.TextButton(enabled = limited > 0, onClick = {
                        scope.launch { creds.forEach { c.router.resetCredential(it.id) } }
                    }) { Text("Reset limit flags" + if (limited > 0) " ($limited)" else "") }
                }
            }
            if (creds.isEmpty()) item { Text("Add keys under Providers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(creds, key = { "k" + it.id }) { k ->
                val now = System.currentTimeMillis()
                val (label, color) = when {
                    !k.enabled -> "Off" to extra.subtle
                    k.status == "invalid" -> "Rejected" to extra.danger
                    k.cooldownUntil > now -> "Cooling ${((k.cooldownUntil - now) / 60_000).coerceAtLeast(1)}m" to extra.warning
                    k.status == "ok" -> "Ready" to extra.success
                    else -> "Unknown" to extra.subtle
                }
                ListRow(cName[k.id] ?: k.label, "${k.requests} ok · ${k.failures} failed · ${k.lastLatencyMs}ms last" + (k.lastError?.let { " · $it" } ?: ""),
                    trailing = { Pill(label, color) })
            }
            item { SectionLabel("Usage · last 7 days") }
            val byKey = usage.groupBy { it.credentialId to it.model }.map { (k, v) ->
                Triple(k, v.sumOf { it.requests } to v.sumOf { it.failures }, v.sumOf { it.inputTokens } to v.sumOf { it.outputTokens })
            }.sortedByDescending { it.second.first }
            if (byKey.isEmpty()) item { Text("No requests yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(byKey, key = { "u" + it.first.first + it.first.second }) { (k, req, tok) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.second, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cName[k.first] ?: pName[k.first] ?: k.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${req.first} req" + if (req.second > 0) " · ${req.second} failed" else "", style = MaterialTheme.typography.labelMedium)
                        Text("${(tok.first / 1000)}k in · ${(tok.second / 1000)}k out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("Token counts come from what each provider reports; some don't report usage while streaming.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
