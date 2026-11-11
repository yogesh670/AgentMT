package com.inyogeshwar.agentmt.feature.chat

import androidx.compose.material3.FilterChip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SwapHoriz
import com.inyogeshwar.agentmt.agent.attachments.Attachment
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.Logo
import com.inyogeshwar.agentmt.core.ui.appViewModel
import com.inyogeshwar.agentmt.feature.projects.ProjectPickerSheet
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "Explain this project" to "Read the code and give me a map",
    "Build a web app" to "A todo app in one index.html",
    "Find bugs" to "Review the code and fix issues",
    "Write a README" to "Document how to run this",
)

@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String?,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenFiles: (projectId: String) -> Unit,
    onSessionCreated: (String) -> Unit,
) {
    val vm = appViewModel(key = "chat-${sessionId ?: "new"}") { ChatViewModel(container, sessionId) }
    val items by vm.items.collectAsState()
    val live by vm.live.collectAsState()
    val session by vm.session.collectAsState()
    val model by vm.currentModel.collectAsState()
    val choices by vm.modelChoices.collectAsState()
    val providers by vm.providers.collectAsState()
    val projects by vm.projects.collectAsState()
    val draftProject by vm.draftProject.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    val profile by vm.currentProfile.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val pending by vm.pending.collectAsState()
    val attachError by vm.attachError.collectAsState()
    val tags by vm.tags.collectAsState()
    val allTags by vm.allTags.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> if (uris.isNotEmpty()) vm.attach(uris) }

    LaunchedEffect(currentId) { if (sessionId == null && currentId != null) onSessionCreated(currentId!!) }

    var input by rememberSaveable { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val projectName = session?.projectName ?: draftProject?.name ?: "Playground"
    val projectId = session?.projectId ?: draftProject?.id

    val totalCount = items.size + (if (live.running) 2 else 0) + (if (live.approval != null) 1 else 0) + (if (live.error != null) 1 else 0)
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) follow = atBottom }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }
    LaunchedEffect(totalCount, live.streamingText.length / 120, live.approval, live.activeTool) {
        if (totalCount > 0 && follow) runCatching { listState.scrollToItem(totalCount + 2, 100_000) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // ---- Top bar
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(56.dp).padding(horizontal = 4.dp)
                .then(Modifier.padding(top = 0.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Menu") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .clickable { showModels = true }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (profile != null) { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(4.dp)) }
                    Text(profile?.name ?: model?.label?.ifBlank { null } ?: "Choose model", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (session == null) showProjects = true else projectId?.let(onOpenFiles) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Outlined.EditNote, "New chat") }
            Box {
                IconButton(onClick = { menu = true }, enabled = session != null) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Files") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { menu = false; projectId?.let(onOpenFiles) })
                    DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = session?.title ?: "" })
                    DropdownMenuItem({ Text("Delete chat", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        session?.takeIf { it.kind == "task" }?.let { s ->
            TaskHeader(s, tags, allTags.map { it.name }, onStatus = vm::setStatus, onInstructions = vm::setInstructions,
                onAddTag = vm::addTag, onRemoveTag = vm::removeTag, skills = vm.skillNames(), onSkills = vm::setSkills)
        }
        AnimatedVisibility(live.running && live.notice != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(com.inyogeshwar.agentmt.app.theme.LocalExtra.current.warning.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(16.dp), tint = com.inyogeshwar.agentmt.app.theme.LocalExtra.current.warning)
                Spacer(Modifier.width(8.dp))
                Text(live.notice.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        // ---- Transcript
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty() && !live.running) {
                Welcome(
                    noProvider = providers.isEmpty(),
                    projectName = projectName,
                    onProject = { showProjects = true },
                    onProviders = onOpenProviders,
                    onSuggestion = { t, s -> input = "$t: $s" },
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
                        when (it) {
                            is ChatItem.User -> UserBubble(it.text, it.attachments)
                            is ChatItem.Assistant -> AssistantBlock(it.text, showAvatar = items.getOrNull(i - 1) !is ChatItem.Assistant && items.getOrNull(i - 1) !is ChatItem.ToolStep,
                                routeNote = it.routeNote.takeIf { _ -> val nx = items.getOrNull(i + 1); (nx == null && !live.running) || nx is ChatItem.User })
                            is ChatItem.ToolStep -> ToolStepRow(it, live.running)
                            is ChatItem.Compacted -> com.inyogeshwar.agentmt.feature.chat.CompactedRow(it.summary)
                        }
                    }
                    if (live.running) {
                        item(key = "reasoning") { if (live.reasoning.isNotBlank() && live.streamingText.isEmpty()) ReasoningBox(live.reasoning) }
                        item(key = "live") {
                            when {
                                live.streamingText.isNotEmpty() -> AssistantBlock(live.streamingText, showAvatar = items.lastOrNull() is ChatItem.User, streaming = true)
                                live.approval != null -> Spacer(Modifier.height(1.dp))
                                live.activeTool != null -> LiveStatus(live.activeTool!!)
                                else -> LiveStatus(if (live.step <= 1) "Thinking…" else "Working… step ${live.step}")
                            }
                        }
                    }
                    live.approval?.let { a -> item(key = "approval") { ApprovalCard(a, vm::answer) } }
                    live.error?.let { e ->
                        item(key = "error") {
                            ErrorCard(e, onRetry = { vm.dismissError(); vm.retryLast() }, onDismiss = vm::dismissError,
                                onFix = if (e.contains("provider", true) || e.contains("API key", true) || e.contains("401")) onOpenProviders else null,
                                onContinue = { vm.dismissError(); vm.continueRun() })
                        }
                    }
                }
                if (!atBottom) SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1)) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) { Icon(Icons.Filled.KeyboardArrowDown, "Scroll to bottom") }
            }
        }

        // ---- Composer
        Column(Modifier.navigationBarsPadding().imePadding()) {
            if (input.startsWith("/") && !input.contains(' ')) {
                val cmds = remember(input) { vm.commands().filter { it.name.startsWith(input.drop(1), true) } }
                if (cmds.isNotEmpty()) SlashPopup(cmds) { input = "/${it.name} " }
            }
            attachError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp).clickable { vm.attachError.value = null }) }
            if (pending.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pending, key = { it.path }) { a -> PendingChip(a) { vm.removePending(a) } }
            }
            val plan by vm.planMode.collectAsState()
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !plan, onClick = { vm.planMode.value = false }, label = { Text("Build") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = plan, onClick = { vm.planMode.value = true }, label = { Text("Plan") })
                if (plan) Text("  read-only: explores and proposes, changes nothing", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Composer(
                value = input, onChange = { input = it }, running = live.running, hasAttachments = pending.isNotEmpty(),
                onAttach = { picker.launch(arrayOf("*/*")) },
                onSend = { vm.send(input); input = ""; follow = true }, onStop = vm::stop,
            )
        }
    }

    if (showModels) ModelSheet(choices, model, vm::pickModel, onOpenProviders, profiles = profiles, currentProfile = profile,
        onPickProfile = vm::pickProfile) { showModels = false }
    if (showProjects) ProjectPickerSheet(container, projects, projectId, vm::pickProject) { showProjects = false }
    renaming?.let { t ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(t, { renaming = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(t); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    if (confirmDelete) com.inyogeshwar.agentmt.core.ui.ConfirmDialog("Delete chat?", "This removes the conversation. Files in the project are not touched.", "Delete", true,
        onConfirm = { vm.delete(onNewChat) }, onDismiss = { confirmDelete = false })
}

@Composable
private fun Welcome(noProvider: Boolean, projectName: String, onProject: () -> Unit, onProviders: () -> Unit, onSuggestion: (String, String) -> Unit) {
    val icons = listOf(
        Icons.AutoMirrored.Outlined.Article,
        Icons.Outlined.Code,
        Icons.Outlined.BugReport,
        Icons.Outlined.Description,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
                .padding(22.dp),
        ) {
            Column {
                Logo(54.dp)
                Spacer(Modifier.height(12.dp))
                Text("AgentMT", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text("Your coding agent, in your pocket.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(Icons.Outlined.Terminal, "Linux")
                    HeroChip(Icons.Outlined.Language, "Web")
                    HeroChip(Icons.Outlined.Extension, "Tools")
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("What are we building?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .clickable(onClick = onProject).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(projectName, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (noProvider) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, onClick = onProviders) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connect a model", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Add an API key for OpenAI, Anthropic, Gemini, OpenRouter or any compatible provider.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Start with", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp))
        suggestions.withIndex().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { entry ->
                    val (title, subtitle) = entry.value
                    SuggestionCard2(title, subtitle, icons[entry.index], { onSuggestion(title, subtitle) }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SuggestionCard2(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(14.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Composer(
    value: String, onChange: (String) -> Unit, running: Boolean, hasAttachments: Boolean, onAttach: () -> Unit,
    onSend: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach, enabled = !running, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, "Attach files", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) Text(if (running) "Agent is working…" else "Message or /command", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 8,
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = (value.isNotBlank() || hasAttachments) && !running
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(
                        when {
                            running -> MaterialTheme.colorScheme.onSurface
                            canSend -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    )
                    .clickable(enabled = running || canSend) { if (running) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                if (running) Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.ArrowUpward, "Send", tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PendingChip(a: Attachment, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(attachmentIcon(a.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp)) }
    }
}

@Composable
private fun SlashPopup(cmds: List<com.inyogeshwar.agentmt.agent.plugins.SlashCommand>, onPick: (com.inyogeshwar.agentmt.agent.plugins.SlashCommand) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).verticalScroll(rememberScrollState()),
    ) {
        cmds.take(20).forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("/${c.name}", fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(c.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
