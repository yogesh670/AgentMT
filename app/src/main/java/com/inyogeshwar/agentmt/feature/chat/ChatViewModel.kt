package com.inyogeshwar.agentmt.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inyogeshwar.agentmt.agent.ApprovalAnswer
import com.inyogeshwar.agentmt.agent.LiveTurn
import com.inyogeshwar.agentmt.agent.attachments.Attachment
import com.inyogeshwar.agentmt.agent.attachments.Attachments
import com.inyogeshwar.agentmt.agent.modelList
import com.inyogeshwar.agentmt.agent.plugins.CommandStore
import com.inyogeshwar.agentmt.agent.plugins.SlashCommand
import com.inyogeshwar.agentmt.agent.routing.meta
import com.inyogeshwar.agentmt.data.db.RoutingProfileEntity
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.data.db.MessageEntity
import com.inyogeshwar.agentmt.data.db.ProjectEntity
import com.inyogeshwar.agentmt.data.db.ProviderEntity
import com.inyogeshwar.agentmt.data.db.SessionRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

/** A rendered transcript entry. */
sealed interface ChatItem {
    val key: String
    data class User(override val key: String, val text: String, val attachments: List<Attachment> = emptyList()) : ChatItem
    data class Assistant(override val key: String, val text: String, val routeNote: String? = null) : ChatItem
    data class ToolStep(override val key: String, val name: String, val args: String, val output: String?, val status: String?) : ChatItem
    data class Compacted(override val key: String, val summary: String) : ChatItem
}

data class ModelChoice(val providerId: String, val providerName: String, val model: String, val alias: String? = null, val vision: Boolean? = null) {
    val label get() = alias ?: model
}

class ChatViewModel(private val c: AppContainer, initialSessionId: String?) : ViewModel() {
    private val sessionId = MutableStateFlow(initialSessionId)
    val currentSessionId: StateFlow<String?> = sessionId

    /** Project for a chat that hasn't been created yet. */
    val draftProject = MutableStateFlow<ProjectEntity?>(null)
    val draftModel = MutableStateFlow<ModelChoice?>(null)

    init {
        viewModelScope.launch {
            if (initialSessionId == null) draftProject.value = lastOrDefault()
        }
    }

    private suspend fun lastOrDefault(): ProjectEntity {
        val recent = c.db.projects().observeAll().first().firstOrNull()
        return recent ?: c.defaultProject()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val session: StateFlow<SessionRow?> = sessionId.flatMapLatest { id -> if (id == null) flowOf(null) else c.db.sessions().observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<ChatItem>> = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.messages().observe(id) }
        .map(::toItems).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val live: StateFlow<LiveTurn> = combine(sessionId, c.engine.live) { id, m -> id?.let { m[it] } ?: LiveTurn() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTurn())

    val providers: StateFlow<List<ProviderEntity>> = c.db.providers().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val projects: StateFlow<List<ProjectEntity>> = c.db.projects().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = c.settings.state

    val profiles: StateFlow<List<RoutingProfileEntity>> = c.db.routing().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val draftProfile = MutableStateFlow<String?>(c.settings.state.value.defaultProfileId)

    /** Name of the routing profile in use, if any. */
    val currentProfile: StateFlow<RoutingProfileEntity?> = combine(session, draftProfile, profiles) { s, dp, list ->
        val id = if (s != null) s.profileId else dp
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tags: StateFlow<List<String>> = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.tags().observeFor("session", id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allTags = c.db.tags().observeCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pending = MutableStateFlow<List<Attachment>>(emptyList())
    val attachError = MutableStateFlow<String?>(null)

    fun attach(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris.take(10)) {
                runCatching { withContext(Dispatchers.IO) { Attachments.import(c.context, u) } }
                    .onSuccess { a -> pending.value = pending.value + a }
                    .onFailure { attachError.value = it.message ?: "Couldn't attach file" }
            }
        }
    }
    fun removePending(a: Attachment) { pending.value = pending.value - a }

    fun commands(): List<SlashCommand> {
        val root = (session.value?.projectPath ?: draftProject.value?.path)?.let { java.io.File(it) }
        return c.commands.all(root)
    }

    val currentModel: StateFlow<ModelChoice?> = combine(session, draftModel, providers, settings) { s, draft, ps, st ->
        val pid = s?.providerId ?: draft?.providerId ?: st.defaultProviderId
        val mid = s?.modelId ?: draft?.model ?: st.defaultModelId
        val p = ps.firstOrNull { it.id == pid } ?: ps.firstOrNull { it.enabled }
        if (p == null) null else (mid ?: p.defaultModel ?: p.modelList().firstOrNull() ?: "").let { m ->
            val meta = p.meta()[m]
            ModelChoice(p.id, p.name, m, meta?.alias, meta?.vision)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val modelChoices: StateFlow<List<ModelChoice>> = providers.map { ps ->
        ps.filter { it.enabled }.flatMap { p -> val meta = p.meta(); p.modelList().map { ModelChoice(p.id, p.name, it, meta[it]?.alias, meta[it]?.vision) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Build (full access) or Plan (read-only) agent, toggled in the composer. */
    val planMode = MutableStateFlow(false)

    fun send(text: String) {
        var t = text.trim()
        val atts = pending.value
        if (t.isEmpty() && atts.isEmpty()) return
        if (t.startsWith("/")) {
            val name = t.drop(1).substringBefore(' ')
            commands().firstOrNull { it.name == name }?.let { cmd -> t = CommandStore.expand(cmd, t.substringAfter(' ', "")) }
        }
        pending.value = emptyList()
        viewModelScope.launch {
            var id = sessionId.value
            if (id == null) {
                val project = draftProject.value ?: c.defaultProject()
                val m = currentModel.value
                id = c.newSession(project.id, m?.providerId, m?.model?.ifBlank { null })
                draftProfile.value?.let { c.db.sessions().setProfile(id, it) }
                c.openProject(project.path)
                sessionId.value = id
            } else {
                // make sure the session has a model
                val m = currentModel.value
                val s = c.db.sessions().get(id)
                if (s != null && s.profileId == null && (s.providerId == null || s.modelId == null) && m != null && m.model.isNotBlank()) c.db.sessions().setModel(id, m.providerId, m.model)
            }
            c.engine.send(id, t, atts, plan = planMode.value)
        }
    }

    fun pickProfile(p: RoutingProfileEntity?) {
        viewModelScope.launch {
            val id = sessionId.value
            if (id != null) c.db.sessions().setProfile(id, p?.id) else draftProfile.value = p?.id
        }
    }

    fun setStatus(status: String) { viewModelScope.launch { sessionId.value?.let { c.db.sessions().setStatus(it, status, System.currentTimeMillis()) } } }
    fun setInstructions(text: String) { viewModelScope.launch { sessionId.value?.let { c.db.sessions().setInstructions(it, text.trim()) } } }
    fun setSkills(names: String) { viewModelScope.launch { sessionId.value?.let { c.db.sessions().setSkills(it, names) } } }
    fun addTag(t: String) { viewModelScope.launch { sessionId.value?.let { c.addTag(t, "session", it) } } }
    fun removeTag(t: String) { viewModelScope.launch { sessionId.value?.let { c.removeTag(t, "session", it) } } }
    fun skillNames(): List<String> = c.skills.all().filter { it.name !in c.settings.state.value.disabledSkills }.map { it.name }

    fun stop() { sessionId.value?.let { c.engine.stop(it) } }
    fun answer(a: ApprovalAnswer) { sessionId.value?.let { c.engine.answer(it, a) } }

    fun answerQuestion(text: String) { sessionId.value?.let { c.engine.answerQuestion(it, text) } }
    fun dismissError() { sessionId.value?.let { c.engine.clearError(it) } }

    fun pickModel(m: ModelChoice) {
        viewModelScope.launch {
            val id = sessionId.value
            if (id != null) c.db.sessions().pinModel(id, m.providerId, m.model) else { draftModel.value = m; draftProfile.value = null }
            c.settings.update { it.copy(defaultProviderId = m.providerId, defaultModelId = m.model) }
        }
    }

    fun pickProject(p: ProjectEntity) {
        if (sessionId.value == null) draftProject.value = p
    }

    fun rename(title: String) { viewModelScope.launch { sessionId.value?.let { c.db.sessions().rename(it, title.trim().ifEmpty { "Chat" }, System.currentTimeMillis()) } } }
    fun delete(onDone: () -> Unit) { viewModelScope.launch { sessionId.value?.let { c.engine.stop(it); c.db.sessions().delete(it); c.db.messages().clear(it) }; onDone() } }

    /** Picks up after an error or limit without throwing away finished steps (works after switching model too). */
    fun continueRun() {
        val id = sessionId.value ?: return
        if (c.engine.isRunning(id)) return
        c.engine.resume(id)
    }

    /** Deletes the last user message and everything after it, then sends it again. */
    fun retryLast() {
        val id = sessionId.value ?: return
        if (c.engine.isRunning(id)) return
        viewModelScope.launch {
            val rows = c.db.messages().list(id)
            val lastUser = rows.lastOrNull { it.role == "user" } ?: return@launch
            c.db.messages().deleteFrom(id, lastUser.id)
            c.engine.send(id, lastUser.text, Attachments.decode(lastUser.attachmentsJson))
        }
    }

    companion object {
        fun toItems(rows: List<MessageEntity>): List<ChatItem> {
            val results = rows.filter { it.role == "tool" }.associateBy { it.toolCallId }
            val out = ArrayList<ChatItem>()
            for (m in rows) {
                when (m.role) {
                    "summary" -> out += ChatItem.Compacted("s${m.id}", m.text)
                    "user" -> out += ChatItem.User("u${m.id}", m.text, Attachments.decode(m.attachmentsJson))
                    "assistant" -> {
                        if (m.text.isNotBlank()) out += ChatItem.Assistant("a${m.id}", m.text, m.routeNote)
                        m.toolCallsJson?.let { j ->
                            val a = JSONArray(j)
                            for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i); val r = results[o.getString("id")]
                                out += ChatItem.ToolStep("t${m.id}_$i", o.getString("name"), o.optString("arguments"), r?.text, r?.status)
                            }
                        }
                    }
                }
            }
            return out
        }
    }
}
