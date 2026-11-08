package com.inyogeshwar.agentmt.agent

import android.content.Context
import com.inyogeshwar.agentmt.agent.attachments.Attachment
import com.inyogeshwar.agentmt.agent.attachments.Attachments
import com.inyogeshwar.agentmt.agent.llm.ChatMsg
import com.inyogeshwar.agentmt.agent.llm.Part
import com.inyogeshwar.agentmt.agent.routing.MidStreamException
import com.inyogeshwar.agentmt.agent.routing.ModelRouter
import com.inyogeshwar.agentmt.agent.tools.TodoTool
import com.inyogeshwar.agentmt.agent.llm.LlmEvent
import com.inyogeshwar.agentmt.agent.llm.LlmRequest
import com.inyogeshwar.agentmt.agent.llm.ToolCall
import com.inyogeshwar.agentmt.agent.mcp.McpClient
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.agent.tools.EditFileTool
import com.inyogeshwar.agentmt.agent.tools.GlobTool
import com.inyogeshwar.agentmt.agent.tools.GrepTool
import com.inyogeshwar.agentmt.agent.tools.ListDirTool
import com.inyogeshwar.agentmt.agent.tools.McpTool
import com.inyogeshwar.agentmt.agent.tools.ReadFileTool
import com.inyogeshwar.agentmt.agent.tools.ShellTool
import com.inyogeshwar.agentmt.agent.tools.SkillTool
import com.inyogeshwar.agentmt.agent.tools.Tool
import com.inyogeshwar.agentmt.agent.tools.ToolContext
import com.inyogeshwar.agentmt.agent.tools.ToolKind
import com.inyogeshwar.agentmt.agent.tools.WebFetchTool
import com.inyogeshwar.agentmt.agent.tools.Workspace
import com.inyogeshwar.agentmt.agent.tools.WriteFileTool
import com.inyogeshwar.agentmt.data.db.AppDatabase
import com.inyogeshwar.agentmt.data.db.MessageEntity
import com.inyogeshwar.agentmt.data.db.RuleEntity
import com.inyogeshwar.agentmt.data.db.RunEntity
import com.inyogeshwar.agentmt.data.prefs.SettingsStore
import com.inyogeshwar.agentmt.data.secure.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PendingApproval(
    val id: String,
    val toolName: String,
    val kind: ToolKind,
    val summary: String,
    val target: String,
    val preview: String?,
)

/** In-memory state of a session's current turn. Everything durable is in Room. */
data class LiveTurn(
    val running: Boolean = false,
    val streamingText: String = "",
    val reasoning: String = "",
    val activeTool: String? = null,
    val approval: PendingApproval? = null,
    val error: String? = null,
    val runId: String? = null,
    val step: Int = 0,
    /** Short routing notice, e.g. "OpenRouter · Main rate limited → trying Backup". */
    val notice: String? = null,
    /** Which provider/key/model is answering now. */
    val servedBy: String? = null,
)

enum class ApprovalAnswer { Once, Always, Deny }

class AgentEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val vault: SecretVault,
    private val settings: SettingsStore,
    private val skills: SkillStore,
    val router: ModelRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<String, Job>()
    private val waiters = HashMap<String, CompletableDeferred<ApprovalAnswer>>()

    private val _live = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())
    val live: StateFlow<Map<String, LiveTurn>> = _live.asStateFlow()

    val builtinTools: List<Tool> = listOf(
        ListDirTool(), ReadFileTool(), GlobTool(), GrepTool(), WriteFileTool(), EditFileTool(), ShellTool(), WebFetchTool(), SkillTool(), TodoTool(),
        com.inyogeshwar.agentmt.agent.tools.BrowserReadTool(), com.inyogeshwar.agentmt.agent.tools.BrowserTool(),
        com.inyogeshwar.agentmt.agent.tools.BackgroundProcessTool(),
    )

    /** The setting, but never more than 75% of the smallest known context window among the candidate models. */
    private fun compactLimitTokens(setting: Int, candidates: List<com.inyogeshwar.agentmt.agent.routing.Candidate>): Long {
        val windows = candidates.mapNotNull { c -> ProviderCatalog.model(c.model)?.context?.takeIf { it > 0 } }
        val cap = windows.minOrNull()?.let { (it * 0.75).toLong() }
        return if (cap != null) minOf(setting.toLong(), cap) else setting.toLong()
    }

    /** Tools that need app services (set by AppContainer). */
    var extraTools: List<Tool> = emptyList()

    /** Extra system-prompt sections (build kit, installed tools), set by AppContainer. */
    var envNote: () -> String = { "" }
    private val voice by lazy { com.inyogeshwar.agentmt.core.DoneVoice(context) }

    init {
        scope.launch { db.runs().cancelOrphans(System.currentTimeMillis()); router.migrateLegacyKeys() }
    }

    private fun set(sessionId: String, f: (LiveTurn) -> LiveTurn) =
        _live.update { it + (sessionId to f(it[sessionId] ?: LiveTurn())) }

    fun isRunning(sessionId: String) = _live.value[sessionId]?.running == true
    fun anyRunning() = _live.value.values.any { it.running }
    fun clearError(sessionId: String) = set(sessionId) { it.copy(error = null) }

    /** [plan] = read-only "plan" agent (like opencode's Tab toggle): no edits, no commands, no MCP calls. */
    fun send(sessionId: String, text: String, attachments: List<Attachment> = emptyList(), plan: Boolean = false) {
        if (isRunning(sessionId) || (text.isBlank() && attachments.isEmpty())) return
        set(sessionId) { LiveTurn(running = true) }
        AgentService.start(context)
        jobs[sessionId] = scope.launch { runTurn(sessionId, text.trim(), attachments, plan) }
    }

    /**
     * Keeps every earlier step (tool results, partial answers) and asks the current model to pick up where the last run stopped.
     * Used after an error, a limit, or a model switch, instead of resending the last message from scratch.
     */
    fun resume(sessionId: String, plan: Boolean = false) = send(sessionId, CONTINUE_TEXT, plan = plan)

    fun stop(sessionId: String) {
        waiters.remove(_live.value[sessionId]?.approval?.id)?.complete(ApprovalAnswer.Deny)
        jobs[sessionId]?.cancel()
    }

    fun answer(sessionId: String, answer: ApprovalAnswer) {
        val a = _live.value[sessionId]?.approval ?: return
        waiters.remove(a.id)?.complete(answer)
        set(sessionId) { it.copy(approval = null) }
    }

    private suspend fun runTurn(sessionId: String, userText: String, attachments: List<Attachment>, plan: Boolean = false) {
        val now = System.currentTimeMillis()
        val session = db.sessions().get(sessionId) ?: return finishLive(sessionId, "Session not found")
        val project = db.projects().get(session.projectId) ?: return finishLive(sessionId, "Project not found")
        val runId = UUID.randomUUID().toString()
        val firstTurn = db.messages().list(sessionId).none { it.role == "user" }
        db.messages().insert(MessageEntity(sessionId = sessionId, role = "user", text = userText, runId = runId, createdAt = now,
            attachmentsJson = Attachments.encode(attachments)))
        if (firstTurn && session.kind != "task") db.sessions().rename(sessionId, titleFrom(userText.ifBlank { attachments.firstOrNull()?.name ?: "Files" }), now)
        else db.sessions().touch(sessionId, now)

        val s = settings.state.value
        val candidates = router.candidatesFor(session)
        var run = RunEntity(runId, sessionId, project.id, userText.ifBlank { "Attached files" }.take(80), "running", now, model = candidates.firstOrNull()?.model)
        db.runs().upsert(run)
        set(sessionId) { it.copy(runId = runId) }

        if (candidates.isEmpty()) {
            db.runs().upsert(run.copy(status = "failed", endedAt = System.currentTimeMillis(), error = "No model selected"))
            return finishLive(sessionId, "Add a provider and pick a model first. Open Settings → Providers.")
        }

        val rootDir = File(project.path)
        if (!rootDir.isDirectory || !rootDir.canRead()) {
            db.runs().upsert(run.copy(status = "failed", endedAt = System.currentTimeMillis(), error = "Folder not accessible"))
            return finishLive(sessionId, "Can't open ${project.path}. Grant All files access in Settings → Storage, or pick another folder.")
        }
        val ws = Workspace(rootDir)
        skills.setProject(ws.root)
        val ctx = ToolContext(ws, skills, sessionId) { json -> db.sessions().setTodos(sessionId, json) }

        val (mcpTools, mcpNote) = loadMcpTools(project.id)
        val tools = (builtinTools + extraTools + mcpTools).let { all -> if (plan) all.filter { it.kind == ToolKind.Read || it.name == "web_fetch" } else all }
        val byName = tools.associateBy { it.name }
        val allowed = session.skills.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val skillList = skills.all().filter { it.name !in s.disabledSkills && (allowed.isEmpty() || it.name in allowed) }
        val taskNote = if (session.kind == "task") buildString {
            append("\n# Current task\nTitle: ${session.title}\nStatus: ${session.status}\n")
            if (session.instructions.isNotBlank()) append("Task instructions:\n${session.instructions}\n")
            append("Track progress with update_todos: break the work into steps, keep exactly one in_progress, mark steps done as you finish them.")
        } else ""
        val planNote = if (plan) "\n# Plan mode\nYou are in read-only plan mode. You cannot edit files or run commands. Explore with the read tools, then answer with a clear numbered plan (files to change, steps, risks). The user switches to Build mode to carry it out." else ""
        val system = SystemPrompt.build(ws, project.name, skillList, s.customInstructions, (mcpNote ?: "") + runCatching { envNote() }.getOrDefault("") + taskNote + planNote)
        val routeLog = StringBuilder()
        fun logRoute(line: String) { routeLog.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("  ").append(line).append('\n') }
        val changed = LinkedHashSet<String>()

        try {
            for (step in 1..s.maxSteps) {
                set(sessionId) { it.copy(step = step, streamingText = "", reasoning = "", activeTool = null) }
                var rows = db.messages().list(sessionId)
                if (compactIfNeeded(sessionId, rows, candidates, compactLimitTokens(s.compactAtTokens, candidates) * 4L)) {
                    set(sessionId) { it.copy(notice = "Context compacted. Earlier steps were summarized; your chat history stays here.") }
                    rows = db.messages().list(sessionId)
                }
                val history = buildHistory(rows) { m, binary -> Attachments.materialize(context, Attachments.decode(m.attachmentsJson), binary) }
                val needsVision = history.any { it.parts.isNotEmpty() }
                val text = StringBuilder(); var calls: List<ToolCall> = emptyList()
                var lastEmit = 0L
                val served = try { router.stream(candidates, LlmRequest("", system, history, tools.map { it.spec() }, reasoning = s.effort), needsVision,
                    onNote = { note -> logRoute(note); set(sessionId) { it.copy(notice = note) } },
                ) { ev ->
                    when (ev) {
                        is LlmEvent.Text -> {
                            text.append(ev.delta)
                            val t = System.currentTimeMillis()
                            if (t - lastEmit > 50) { lastEmit = t; set(sessionId) { it.copy(streamingText = text.toString()) } }
                        }
                        is LlmEvent.Reasoning -> set(sessionId) { it.copy(reasoning = (it.reasoning + ev.delta).takeLast(4000)) }
                        is LlmEvent.ToolCalls -> calls = ev.calls
                        is LlmEvent.Usage -> run = run.copy(inputTokens = run.inputTokens + ev.input, outputTokens = run.outputTokens + ev.output)
                        is LlmEvent.Finish -> Unit
                    }
                } } catch (e: com.inyogeshwar.agentmt.agent.routing.NoRouteException) {
                    // The conversation outgrew the model: summarize harder once and redo this step.
                    if (text.isEmpty() && e.message.orEmpty().contains("context too long") &&
                        compactIfNeeded(sessionId, db.messages().list(sessionId), candidates, s.compactAtTokens * 2L)) {
                        logRoute("context too long → compacted, retrying step $step")
                        set(sessionId) { it.copy(notice = "Context compacted. Retrying…") }
                        val rows2 = db.messages().list(sessionId)
                        val h2 = buildHistory(rows2) { m, binary -> Attachments.materialize(context, Attachments.decode(m.attachmentsJson), binary) }
                        router.stream(candidates, LlmRequest("", system, h2, tools.map { it.spec() }, reasoning = s.effort), h2.any { it.parts.isNotEmpty() },
                            onNote = { note -> logRoute(note) }) { ev ->
                            when (ev) {
                                is LlmEvent.Text -> { text.append(ev.delta); set(sessionId) { it.copy(streamingText = text.toString()) } }
                                is LlmEvent.Reasoning -> Unit
                                is LlmEvent.ToolCalls -> calls = ev.calls
                                is LlmEvent.Usage -> run = run.copy(inputTokens = run.inputTokens + ev.input, outputTokens = run.outputTokens + ev.output)
                                is LlmEvent.Finish -> Unit
                            }
                        }
                    } else throw e
                }
                logRoute("step $step served by ${served.display}")
                set(sessionId) { it.copy(servedBy = served.display) }
                run = run.copy(model = served.model, routeLog = routeLog.toString())
                db.messages().insert(MessageEntity(
                    sessionId = sessionId, role = "assistant", text = text.toString(), runId = runId, routeNote = served.display,
                    toolCallsJson = if (calls.isEmpty()) null else JSONArray().apply {
                        calls.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments)) }
                    }.toString(),
                    createdAt = System.currentTimeMillis(),
                ))
                set(sessionId) { it.copy(streamingText = "") }
                run = run.copy(steps = step)
                db.runs().upsert(run)
                if (calls.isEmpty()) break

                for (call in calls) {
                    val tool = byName[call.name]
                    val args = runCatching { JSONObject(call.arguments) }.getOrNull()
                    if (tool == null || args == null) {
                        insertTool(sessionId, runId, call, if (tool == null) "Unknown tool ${call.name}" else "Arguments were not valid JSON", "error")
                        continue
                    }
                    val target = tool.target(args)
                    val (decision, _) = PermissionEngine.decide(tool.name, tool.kind, target, settings.state.value.approvalMode, db.rules().list())
                    val allowed = when (decision) {
                        Decision.Allow -> true
                        Decision.Deny -> false
                        Decision.Ask -> {
                            val pa = PendingApproval(UUID.randomUUID().toString(), tool.name, tool.kind, tool.summary(args), target,
                                runCatching { tool.preview(args, ctx) }.getOrNull())
                            val d = CompletableDeferred<ApprovalAnswer>()
                            waiters[pa.id] = d
                            db.runs().upsert(run.copy(status = "waiting"))
                            set(sessionId) { it.copy(approval = pa) }
                            val ans = d.await()
                            set(sessionId) { it.copy(approval = null) }
                            db.runs().upsert(run.copy(status = "running"))
                            if (ans == ApprovalAnswer.Always) db.rules().insert(RuleEntity(
                                tool = tool.name, pattern = target, action = "allow",
                                sortOrder = db.rules().maxOrder() + 1, createdAt = System.currentTimeMillis()))
                            ans != ApprovalAnswer.Deny
                        }
                    }
                    if (!allowed) {
                        insertTool(sessionId, runId, call, "The user denied this ${tool.name} call. Do not retry it; continue another way or ask the user.", "denied")
                        continue
                    }
                    set(sessionId) { it.copy(activeTool = tool.summary(args)) }
                    val result = try { tool.run(args, ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        com.inyogeshwar.agentmt.agent.tools.ToolResult("Error: ${e.message ?: e.javaClass.simpleName}", true)
                    }
                    result.changedPath?.let { changed += it }
                    insertTool(sessionId, runId, call, result.text, if (result.isError) "error" else "ok")
                    run = run.copy(toolCalls = run.toolCalls + 1, changedFiles = changed.joinToString("\n"))
                    db.runs().upsert(run)
                }
                if (step == s.maxSteps) {
                    db.messages().insert(MessageEntity(sessionId = sessionId, role = "assistant", runId = runId, createdAt = System.currentTimeMillis(),
                        text = "_Stopped after ${s.maxSteps} steps. Send “continue” to keep going._"))
                }
            }
            db.runs().upsert(run.copy(status = "succeeded", endedAt = System.currentTimeMillis(), changedFiles = changed.joinToString("\n"), routeLog = routeLog.toString()))
            finishLive(sessionId, null)
            if (settings.state.value.voiceDone) withContext(Dispatchers.Main) { runCatching { voice.speak() } }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val partial = _live.value[sessionId]?.streamingText.orEmpty()
                if (partial.isNotBlank()) db.messages().insert(MessageEntity(sessionId = sessionId, role = "assistant", runId = runId,
                    text = "$partial\n\n_(stopped)_", createdAt = System.currentTimeMillis()))
                db.runs().upsert(run.copy(status = "cancelled", endedAt = System.currentTimeMillis(), changedFiles = changed.joinToString("\n"), routeLog = routeLog.toString()))
                finishLive(sessionId, null)
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is MidStreamException -> "The answer stopped halfway (${e.failure.label}). Tap Continue to pick up from here, or switch model first."
                else -> e.message ?: e.javaClass.simpleName
            }
            withContext(NonCancellable) {
                val partial = _live.value[sessionId]?.streamingText.orEmpty()
                if (partial.isNotBlank()) db.messages().insert(MessageEntity(sessionId = sessionId, role = "assistant", runId = runId,
                    text = "$partial\n\n_(interrupted)_", createdAt = System.currentTimeMillis()))
            }
            logRoute("failed: ${msg.take(200)}")
            db.runs().upsert(run.copy(status = "failed", endedAt = System.currentTimeMillis(), error = msg, changedFiles = changed.joinToString("\n"), routeLog = routeLog.toString()))
            finishLive(sessionId, msg)
        }
    }

    /**
     * When the transcript since the last summary is longer than [budgetChars], asks the model for a summary of the older
     * part and stores it as a "summary" row. Rows stay in the database, so the chat keeps showing everything.
     */
    private suspend fun compactIfNeeded(sessionId: String, rows: List<MessageEntity>, candidates: List<com.inyogeshwar.agentmt.agent.routing.Candidate>, budgetChars: Long): Boolean {
        val cut = compactionCut(rows, budgetChars) ?: return false
        val prev = rows.lastOrNull { it.role == "summary" }
        val from = prev?.toolCallId?.toLongOrNull() ?: Long.MIN_VALUE
        val old = rows.filter { it.role != "summary" && it.id >= from && it.id < cut }
        if (old.isEmpty()) return false
        val transcript = buildString {
            prev?.let { append("Earlier summary:\n").append(it.text).append("\n\n") }
            old.forEach { m -> when (m.role) {
                "user" -> append("USER: ").append(m.text.take(6000)).append('\n')
                "assistant" -> { if (m.text.isNotBlank()) append("ASSISTANT: ").append(m.text.take(4000)).append('\n')
                    m.toolCallsJson?.let { append("TOOL CALLS: ").append(it.take(1500)).append('\n') } }
                "tool" -> append("TOOL ${m.toolName} (${m.status}): ").append(m.text.take(1500)).append('\n')
            } }
        }.takeLast(400_000)
        val out = StringBuilder()
        runCatching {
            router.stream(candidates, LlmRequest("", COMPACT_PROMPT, listOf(ChatMsg("user", transcript)), emptyList(), reasoning = "auto"), false,
                onNote = {}) { ev -> if (ev is LlmEvent.Text) out.append(ev.delta) }
        }.onFailure { return false }
        if (out.isBlank()) return false
        db.messages().insert(MessageEntity(sessionId = sessionId, role = "summary", text = out.toString().trim(), toolCallId = cut.toString(),
            createdAt = System.currentTimeMillis()))
        return true
    }

    private suspend fun insertTool(sessionId: String, runId: String, call: ToolCall, text: String, status: String) {
        db.messages().insert(MessageEntity(sessionId = sessionId, role = "tool", text = text, toolCallId = call.id, toolName = call.name,
            toolArgs = call.arguments, status = status, runId = runId, createdAt = System.currentTimeMillis()))
    }

    private fun finishLive(sessionId: String, error: String?) {
        set(sessionId) { LiveTurn(running = false, error = error) }
        jobs.remove(sessionId)
        if (!anyRunning()) AgentService.stop(context)
    }

    private suspend fun loadMcpTools(projectId: String): Pair<List<Tool>, String?> {
        val disabledPlugins = db.plugins().all().filter { !it.enabled }.map { it.id }.toSet()
        val servers = db.mcp().enabled().filter { (it.projectId == null || it.projectId == projectId) && it.pluginId !in disabledPlugins }
        if (servers.isEmpty()) return emptyList<Tool>() to null
        val out = ArrayList<Tool>(); val failed = ArrayList<String>()
        for (srv in servers) {
            runCatching {
                val c = McpClient(srv.url, vault.get("mcp:${srv.id}"))
                val slug = srv.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20).ifEmpty { "srv" }
                c.listTools().forEach { out += McpTool(slug, srv.name, it, c) }
                db.mcp().upsert(srv.copy(lastStatus = "ok"))
            }.onFailure { failed += srv.name; db.mcp().upsert(srv.copy(lastStatus = it.message?.take(120) ?: "error")) }
        }
        val note = if (failed.isEmpty()) null else "\n# MCP\nThese MCP servers could not be reached this turn: ${failed.joinToString()}."
        return out to note
    }

    companion object {
        const val CONTINUE_TEXT = "Continue from where you stopped. Earlier steps and tool results above are done; do not repeat them. " +
            "Check the last results, then carry on with the remaining work."
        const val COMPACT_PROMPT = "You compress the history of a coding agent's session so it can continue without the full transcript. " +
            "Write a dense summary: the user's goal and every requirement they gave, decisions made, files created or changed (paths), " +
            "commands run and their important results or errors, what is done, what is in progress, and the exact next steps. " +
            "Keep names, paths, versions, URLs and error messages verbatim. No preamble."

        /** Rough size of what will be sent: rows after the last summary. */
        fun sizeSinceSummary(rows: List<MessageEntity>): Long {
            val from = rows.lastOrNull { it.role == "summary" }?.toolCallId?.toLongOrNull() ?: Long.MIN_VALUE
            return rows.filter { it.role != "summary" && it.id >= from }.sumOf { (it.text.length + (it.toolCallsJson?.length ?: 0)).toLong() }
        }

        /**
         * Id of the first row to keep verbatim when compacting, or null when no compaction is needed. The kept tail starts at a
         * user or assistant row (never between a tool call and its result) and is at most a third of the budget.
         */
        fun compactionCut(rows: List<MessageEntity>, budgetChars: Long): Long? {
            if (sizeSinceSummary(rows) <= budgetChars) return null
            val from = rows.lastOrNull { it.role == "summary" }?.toolCallId?.toLongOrNull() ?: Long.MIN_VALUE
            val live = rows.filter { it.role != "summary" && it.id >= from }
            var tail = 0L; var cut: Long? = null
            for (i in live.indices.reversed()) {
                tail += live[i].text.length + (live[i].toolCallsJson?.length ?: 0)
                if (tail > budgetChars / 3) break
                if (live[i].role != "tool" && i > 0) cut = live[i].id
            }
            return cut ?: live.lastOrNull { it.role != "tool" && it.id != live.first().id }?.id
        }

        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            return if (line.length <= 48) line.ifEmpty { "New chat" } else line.take(46).substringBeforeLast(' ') + "…"
        }

        /** Rebuilds the provider-neutral transcript. Older tool outputs are trimmed to keep context small. */
        fun buildHistory(
            rows: List<MessageEntity>,
            materialize: (MessageEntity, Boolean) -> Pair<String, List<Part>> = { _, _ -> "" to emptyList() },
        ): List<ChatMsg> {
            val out = ArrayList<ChatMsg>()
            val summary = rows.lastOrNull { it.role == "summary" }
            @Suppress("NAME_SHADOWING")
            val rows = if (summary == null) rows else {
                val keep = summary.toolCallId?.toLongOrNull() ?: Long.MIN_VALUE
                out += ChatMsg("user", "[Summary of the earlier part of this session, written when the context was compacted]\n" + summary.text)
                rows.filter { it.role != "summary" && it.id >= keep }.let { tail ->
                    // A kept tail that opens with the assistant still reads fine after the summary; nothing else to fix.
                    tail
                }
            }
            val cutoff = rows.size - 12
            // Only the two most recent user messages resend images/PDFs; older ones become a short note.
            val binaryFrom = rows.withIndex().filter { it.value.role == "user" && it.value.attachmentsJson != null }.map { it.index }.takeLast(2).firstOrNull() ?: Int.MAX_VALUE
            val answered = rows.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
            rows.forEachIndexed { i, m ->
                when (m.role) {
                    "user" -> if (m.attachmentsJson.isNullOrBlank()) out += ChatMsg("user", m.text) else {
                        val (extra, parts) = materialize(m, i >= binaryFrom)
                        out += ChatMsg("user", m.text + extra, parts)
                    }
                    "assistant" -> {
                        val calls = m.toolCallsJson?.let { j ->
                            val a = JSONArray(j); (0 until a.length()).map { a.getJSONObject(it) }
                                .map { ToolCall(it.getString("id"), it.getString("name"), it.optString("arguments", "{}")) }
                        }.orEmpty().filter { it.id in answered }
                        if (m.text.isNotBlank() || calls.isNotEmpty()) out += ChatMsg("assistant", m.text, toolCalls = calls)
                    }
                    "tool" -> {
                        val t = if (i < cutoff && m.text.length > 4000) m.text.take(4000) + "\n… (trimmed)" else m.text
                        out += ChatMsg("tool", t, toolCallId = m.toolCallId, toolName = m.toolName, isError = m.status != "ok")
                    }
                }
            }
            return out
        }
    }
}
