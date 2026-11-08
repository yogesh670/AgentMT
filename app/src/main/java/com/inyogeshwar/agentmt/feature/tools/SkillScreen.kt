package com.inyogeshwar.agentmt.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.Markdown

@Composable
fun SkillScreen(c: AppContainer, name: String?, onBack: () -> Unit) {
    val skill = remember(name) { name?.let { c.skills.find(it) } }
    if (skill != null) {
        var confirm by remember { mutableStateOf(false) }
        val body = remember(skill) { runCatching { c.skills.content(skill) }.getOrDefault("") }
        AppScaffold(skill.name, onBack, actions = {
            if (skill.source == "Yours") IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("${skill.source} skill", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                Markdown(body.substringAfter("\n---", body).trim())
            }
        }
        if (confirm) ConfirmDialog("Delete skill?", "${skill.name} will be removed.", "Delete", true, onConfirm = { c.skills.delete(skill); onBack() }, onDismiss = { confirm = false })
        return
    }
    var n by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("# Steps\n\n1. ") }
    AppScaffold("New skill", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(n, { n = it }, label = { Text("Name (e.g. android-release)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(d, { d = it }, label = { Text("When should the agent use it?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b, { b = it }, label = { Text("Instructions (Markdown)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp))
            Button(enabled = n.isNotBlank() && d.isNotBlank(), onClick = {
                c.skills.save(n, "---\nname: ${n.trim()}\ndescription: ${d.trim().replace("\n", " ")}\n---\n\n${b.trim()}\n"); onBack()
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save skill") }
        }
    }
}
