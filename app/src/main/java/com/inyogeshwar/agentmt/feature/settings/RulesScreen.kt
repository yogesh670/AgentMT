package com.inyogeshwar.agentmt.feature.settings

import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.EmptyState
import com.inyogeshwar.agentmt.core.ui.ListRow
import com.inyogeshwar.agentmt.core.ui.Pill
import com.inyogeshwar.agentmt.data.db.RuleEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(c: AppContainer, onBack: () -> Unit) {
    val rules by c.db.rules().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    val extra = LocalExtra.current
    AppScaffold("Permission rules", onBack, floating = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add rule") })
    }) { pad ->
        if (rules.isEmpty()) EmptyState(Icons.AutoMirrored.Outlined.Rule, "No rules yet",
            "Rules run top to bottom; the first match wins. Tapping “Always allow” on an approval adds one here.", Modifier.padding(pad))
        else LazyColumn(contentPadding = pad) {
            items(rules, key = { it.id }) { r ->
                ListRow(if (r.tool == "*") "Any tool" else r.tool, r.pattern.ifBlank { "*" }, trailing = {
                    Pill(r.action.replaceFirstChar { it.uppercase() }, when (r.action) { "allow" -> extra.success; "deny" -> extra.danger; else -> extra.warning })
                    IconButton(onClick = { scope.launch { c.db.rules().delete(r) } }) { Icon(Icons.Outlined.Delete, "Delete rule") }
                })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (adding) {
        var tool by remember { mutableStateOf("*") }
        var pattern by remember { mutableStateOf("") }
        var action by remember { mutableStateOf("allow") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tool", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf("*") + c.engine.builtinTools.map { it.name }).forEach { t -> FilterChip(tool == t, { tool = t }, label = { Text(if (t == "*") "Any" else t) }) }
                    }
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("Path or command pattern") }, singleLine = true,
                        supportingText = { Text("Paths use globs (src/**). Commands use * wildcards (ls *). Blank = everything.") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("allow", "ask", "deny").forEach { a -> FilterChip(action == a, { action = a }, label = { Text(a.replaceFirstChar { it.uppercase() }) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { c.db.rules().insert(RuleEntity(tool = tool, pattern = pattern.trim().ifEmpty { "*" }, action = action, sortOrder = c.db.rules().maxOrder() + 1, createdAt = System.currentTimeMillis())) }
                    adding = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
