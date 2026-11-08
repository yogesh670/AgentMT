package com.inyogeshwar.agentmt.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.normalizeTag
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.SUGGESTED_TAGS
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import com.inyogeshwar.agentmt.feature.projects.ProjectPickerSheet
import kotlinx.coroutines.launch

data class TaskTemplate(val label: String, val goalHint: String, val instructions: String, val tags: List<String>)

val TASK_TEMPLATES = listOf(
    TaskTemplate("Build a website", "A landing page for my bakery with menu and contact form",
        "Create a responsive static site (index.html, styles.css, script.js). Semantic HTML, accessible colours, no external build step. Plan first, then create files.", listOf("frontend")),
    TaskTemplate("Mobile app", "An Android notes app in Kotlin + Compose",
        "Follow the platform's standard project layout. Explain anything that must be built on a computer (Gradle builds can't run on the phone).", listOf("android")),
    TaskTemplate("Analyze a GitHub repo", "https://github.com/owner/repo — how is it structured?",
        "Use web_fetch on https://api.github.com/repos/<owner>/<repo>, its README and key source files (raw.githubusercontent.com). Summarize architecture, entry points and notable patterns with links.", listOf("research")),
    TaskTemplate("Debug", "The app crashes when I tap Save",
        "Use the systematic-debugging skill. Reproduce from code reading, find the root cause, propose the smallest fix, then apply it.", listOf("debugging")),
    TaskTemplate("Documentation", "Write a README and usage guide",
        "Read the code first. Write accurate docs only — never invent features. Use Markdown.", listOf("documentation")),
    TaskTemplate("New feature", "Add search to the list screen",
        "Plan the change, list the files you will touch, then implement in small steps. Keep existing style.", listOf("feature")),
    TaskTemplate("Research", "Compare three ways to add offline sync",
        "Use web_fetch on primary sources. Cite each claim with a link. Finish with a recommendation.", listOf("research")),
    TaskTemplate("Video / content", "A 60-second YouTube Short about my app",
        "Produce: hook, script with timings, shot list, on-screen text, captions as an .srt file, title, description, tags and 3 thumbnail concepts. Save files in /video.", listOf("video", "design")),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(c: AppContainer, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val projects by remember { c.db.projects().observeAll() }.collectAsState(initial = emptyList())
    val known by remember { c.db.tags().observeCounts() }.collectAsState(initial = emptyList())
    val profiles by remember { c.db.routing().observeAll() }.collectAsState(initial = emptyList())
    var template by remember { mutableStateOf<TaskTemplate?>(null) }
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(setOf<String>()) }
    var tagInput by remember { mutableStateOf("") }
    var project by remember { mutableStateOf<ProjectEntity?>(null) }
    var pickProject by remember { mutableStateOf(false) }
    var profileId by remember { mutableStateOf(c.settings.state.value.defaultProfileId) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(projects) { if (project == null) project = projects.firstOrNull() }

    AppScaffold("New task", onBack) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Start from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TASK_TEMPLATES.forEach { t ->
                    FilterChip(template == t, {
                        template = t; if (instructions.isBlank() || TASK_TEMPLATES.any { it.instructions == instructions }) instructions = t.instructions
                        tags = tags + t.tags
                        if (title.isBlank()) title = t.label
                    }, label = { Text(t.label) })
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Task name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(goal, { goal = it }, label = { Text("What should the agent do?") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(template?.goalHint ?: "Describe the outcome you want") })
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions & constraints (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            AssistChip(onClick = { pickProject = true }, label = { Text(project?.name ?: "Choose project") }, leadingIcon = { Icon(Icons.Outlined.Folder, null) })
            Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (tags + known.map { it.name } + SUGGESTED_TAGS).distinct().take(16).forEach { t ->
                    FilterChip(t in tags, { tags = if (t in tags) tags - t else tags + t }, label = { Text("#$t") })
                }
            }
            OutlinedTextField(tagInput, { tagInput = it }, label = { Text("Add tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { androidx.compose.material3.TextButton(enabled = normalizeTag(tagInput) != null, onClick = { tags = tags + normalizeTag(tagInput)!!; tagInput = "" }) { Text("Add") } })
            if (profiles.isNotEmpty()) {
                Text("Model routing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(profileId == null, { profileId = null }, label = { Text("Default model") })
                    profiles.forEach { p -> FilterChip(profileId == p.id, { profileId = p.id }, label = { Text(p.name) }) }
                }
            }
            Button(enabled = !busy && goal.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val proj = project ?: c.defaultProject()
                    val id = c.newTask(proj.id, title.ifBlank { goal.take(48) }, instructions, tags.toList(), null, null, profileId, "")
                    c.engine.send(id, goal)
                    onCreated(id)
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Start task") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickProject) ProjectPickerSheet(c, projects, project?.id, onPick = { project = it }) { pickProject = false }
}
