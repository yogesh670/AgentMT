package com.inyogeshwar.agentmt.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/** Default for tools that change things when no rule matches. */
enum class ApprovalMode(val label: String, val desc: String) {
    AskWrites("Ask before changes", "Reads run freely. Writes, edits and commands ask first."),
    AskAll("Ask for everything", "Every tool call asks first."),
    AutoEdits("Auto-approve edits", "File edits run freely. Commands and network still ask."),
    AutoAll("Auto-approve everything", "Every tool runs without asking. Deny rules still apply."),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val approvalMode: ApprovalMode = ApprovalMode.AskWrites,
    val defaultProviderId: String? = null,
    val defaultModelId: String? = null,
    val maxSteps: Int = 25,
    val onboarded: Boolean = false,
    val keepAwake: Boolean = true,
    val customInstructions: String = "",
    val reasoning: String = "medium",
    /** Profile used for new chats instead of a single model. */
    val defaultProfileId: String? = null,
    /** Profile appended after a pinned model when it fails. */
    val fallbackProfileId: String? = null,
    /** On a rate limit, try the provider's other keys before moving on. */
    val rotateKeys: Boolean = true,
    /** Upstream calls per step, including the first. */
    val maxAttempts: Int = 4,
    val disabledSkills: Set<String> = emptySet(),
    val githubTokenSet: Boolean = false,
    /** Reasoning effort for models that support it: auto (don't send), low, medium, high. */
    val effort: String = "auto",
    /** Speak "Your task is completed" when a run finishes. */
    val voiceDone: Boolean = false,
    /** Summarize older steps when the conversation since the last summary passes about this many tokens. */
    val compactAtTokens: Int = 60_000,
    /** Run commands and local MCP servers in the user's own Termux app instead of the built-in Linux. */
    val useTermux: Boolean = false,
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System),
        dynamicColor = p.getBoolean("dynamic", false),
        approvalMode = runCatching { ApprovalMode.valueOf(p.getString("approval", "AskWrites")!!) }.getOrDefault(ApprovalMode.AskWrites),
        defaultProviderId = p.getString("defProvider", null),
        defaultModelId = p.getString("defModel", null),
        maxSteps = p.getInt("maxSteps", 25),
        compactAtTokens = p.getInt("compactAtTokens", 60_000),
        useTermux = p.getBoolean("useTermux", false),
        onboarded = p.getBoolean("onboarded", false),
        keepAwake = p.getBoolean("keepAwake", true),
        customInstructions = p.getString("instructions", "") ?: "",
        reasoning = p.getString("reasoning", "medium") ?: "medium",
        defaultProfileId = p.getString("defProfile", null),
        fallbackProfileId = p.getString("fallbackProfile", null),
        rotateKeys = p.getBoolean("rotateKeys", true),
        maxAttempts = p.getInt("maxAttempts", 4),
        disabledSkills = p.getStringSet("disabledSkills", emptySet())?.toSet() ?: emptySet(),
        githubTokenSet = p.getBoolean("githubToken", false),
        effort = p.getString("effort", "auto") ?: "auto",
        voiceDone = p.getBoolean("voiceDone", false),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        p.edit()
            .putString("theme", n.theme.name)
            .putBoolean("dynamic", n.dynamicColor)
            .putString("approval", n.approvalMode.name)
            .putString("defProvider", n.defaultProviderId)
            .putString("defModel", n.defaultModelId)
            .putInt("maxSteps", n.maxSteps).putInt("compactAtTokens", n.compactAtTokens).putBoolean("useTermux", n.useTermux)
            .putBoolean("onboarded", n.onboarded)
            .putBoolean("keepAwake", n.keepAwake)
            .putString("instructions", n.customInstructions)
            .putString("reasoning", n.reasoning)
            .putString("defProfile", n.defaultProfileId)
            .putString("fallbackProfile", n.fallbackProfileId)
            .putBoolean("rotateKeys", n.rotateKeys)
            .putInt("maxAttempts", n.maxAttempts)
            .putStringSet("disabledSkills", n.disabledSkills)
            .putBoolean("githubToken", n.githubTokenSet)
            .putString("effort", n.effort)
            .putBoolean("voiceDone", n.voiceDone)
            .apply()
        _state.value = n
    }
}

/** Settings as JSON for workspace export (no secrets live here). */
fun AppSettings.toJson(): org.json.JSONObject = org.json.JSONObject()
    .put("theme", theme.name).put("dynamicColor", dynamicColor).put("approvalMode", approvalMode.name)
    .put("defaultProviderId", defaultProviderId).put("defaultModelId", defaultModelId).put("maxSteps", maxSteps).put("compactAtTokens", compactAtTokens).put("useTermux", useTermux)
    .put("keepAwake", keepAwake).put("customInstructions", customInstructions)
    .put("defaultProfileId", defaultProfileId).put("fallbackProfileId", fallbackProfileId)
    .put("rotateKeys", rotateKeys).put("maxAttempts", maxAttempts)
    .put("disabledSkills", org.json.JSONArray(disabledSkills.toList()))
    .put("effort", effort).put("voiceDone", voiceDone)

fun AppSettings.mergeJson(o: org.json.JSONObject): AppSettings = copy(
    theme = runCatching { ThemeMode.valueOf(o.getString("theme")) }.getOrDefault(theme),
    dynamicColor = o.optBoolean("dynamicColor", dynamicColor),
    approvalMode = runCatching { ApprovalMode.valueOf(o.getString("approvalMode")) }.getOrDefault(approvalMode),
    defaultProviderId = o.optString("defaultProviderId").ifBlank { null } ?: defaultProviderId,
    defaultModelId = o.optString("defaultModelId").ifBlank { null } ?: defaultModelId,
    maxSteps = o.optInt("maxSteps", maxSteps),
    compactAtTokens = o.optInt("compactAtTokens", compactAtTokens),
    useTermux = o.optBoolean("useTermux", useTermux),
    keepAwake = o.optBoolean("keepAwake", keepAwake),
    customInstructions = o.optString("customInstructions", customInstructions),
    defaultProfileId = o.optString("defaultProfileId").ifBlank { null } ?: defaultProfileId,
    fallbackProfileId = o.optString("fallbackProfileId").ifBlank { null } ?: fallbackProfileId,
    rotateKeys = o.optBoolean("rotateKeys", rotateKeys),
    maxAttempts = o.optInt("maxAttempts", maxAttempts),
    disabledSkills = o.optJSONArray("disabledSkills")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: disabledSkills,
    effort = o.optString("effort", effort).ifBlank { effort },
    voiceDone = o.optBoolean("voiceDone", voiceDone),
    onboarded = true,
)
