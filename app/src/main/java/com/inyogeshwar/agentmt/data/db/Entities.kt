package com.inyogeshwar.agentmt.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A workspace folder the agent may work in. [path] is an absolute filesystem path. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
)

@Entity(tableName = "sessions", indices = [Index("projectId"), Index("updatedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** chat | task */
    @ColumnInfo(defaultValue = "chat") val kind: String = "chat",
    /** For tasks: todo | active | blocked | done */
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    /** Task-specific instructions added to the system prompt. */
    @ColumnInfo(defaultValue = "") val instructions: String = "",
    /** JSON array of {content,status} written by the update_todos tool. */
    @ColumnInfo(defaultValue = "") val todosJson: String = "",
    /** Routing profile; when set it wins over providerId/modelId. */
    val profileId: String? = null,
    /** Comma-separated skill names allowed for this task; blank = all enabled skills. */
    @ColumnInfo(defaultValue = "") val skills: String = "",
)

/**
 * One transcript entry. role = user | assistant | tool.
 * Assistant rows may carry [toolCallsJson] (JSON array of {id,name,arguments}).
 * Tool rows carry [toolCallId], [toolName] and the tool output in [text].
 */
@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val text: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    /** ok | error | denied, for tool rows. */
    val status: String? = null,
    val runId: String? = null,
    val createdAt: Long,
    /** JSON array of attachments {name,mime,path(relative to filesDir),size,kind}. */
    val attachmentsJson: String? = null,
    /** Which provider/key/model produced an assistant message (no secrets). */
    val routeNote: String? = null,
)

@Entity(tableName = "runs", indices = [Index("sessionId"), Index("startedAt")])
data class RunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val projectId: String,
    val title: String,
    /** running | waiting | succeeded | failed | cancelled */
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    /** Newline-separated relative paths written during the run. */
    val changedFiles: String = "",
    val error: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val model: String? = null,
    /** Routing attempts, one per line, without secrets. */
    @ColumnInfo(defaultValue = "") val routeLog: String = "",
)

/** A model provider. API keys live in the Keystore-backed vault keyed by [id], never here. */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** openai (any OpenAI-compatible endpoint) | anthropic */
    val kind: String,
    val baseUrl: String,
    val models: String = "",
    val defaultModel: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** JSON object model -> {alias, vision, tools, ctx}. Unknown facts are absent. */
    @ColumnInfo(defaultValue = "") val modelMeta: String = "",
)

/** Ordered permission rule. tool = tool name or *, pattern = glob over the target (path or command). */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tool: String,
    val pattern: String,
    /** allow | ask | deny */
    val action: String,
    val sortOrder: Int,
    val createdAt: Long,
)

/** Remote MCP server over Streamable HTTP. A bearer token, if any, is stored in the vault as "mcp:<id>". */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val toolsCache: String = "",
    val lastStatus: String? = null,
    val createdAt: Long,
    /** Null = all projects. */
    val projectId: String? = null,
    /** Set when the server came from a plugin. */
    val pluginId: String? = null,
)

/** One API key for a provider. The secret lives in the vault as "cred:<id>". */
@Entity(tableName = "credentials", indices = [Index("providerId")])
data class CredentialEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val label: String,
    val priority: Int,
    val enabled: Boolean = true,
    /** unknown | ok | invalid | rate_limited | no_credit | error */
    val status: String = "unknown",
    val cooldownUntil: Long = 0,
    val lastError: String? = null,
    val lastUsedAt: Long = 0,
    val requests: Long = 0,
    val failures: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val lastLatencyMs: Long = 0,
    val createdAt: Long,
)

/** Ordered list of provider/model/key candidates. */
@Entity(tableName = "routing_profiles")
data class RoutingProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ordered | pinned */
    val mode: String,
    /** JSON array of {providerId, model, credentialId?} */
    val candidatesJson: String,
    val createdAt: Long,
)

/** Daily usage per key and model. */
@Entity(tableName = "usage", primaryKeys = ["day", "credentialId", "model"])
data class UsageEntity(
    val day: String,
    val credentialId: String,
    val model: String,
    val providerId: String,
    val requests: Long = 0,
    val failures: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

@Entity(tableName = "tags")
data class TagEntity(@PrimaryKey val name: String, val color: Int, val createdAt: Long)

/** kind = session | file | project. For files ref = "<projectId>:<relative path>". */
@Entity(tableName = "tag_links", primaryKeys = ["tag", "kind", "ref"], indices = [Index("ref")])
data class TagLinkEntity(val tag: String, val kind: String, val ref: String)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val description: String,
    val source: String,
    val enabled: Boolean = false,
    /** Folder inside filesDir/plugins. */
    val dir: String,
    /** JSON summary: skills, commands, mcp (http), unsupported (stdio servers, hooks…). */
    val summaryJson: String,
    val installedAt: Long,
)
