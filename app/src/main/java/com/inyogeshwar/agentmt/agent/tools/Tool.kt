package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.agent.llm.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** What a tool does, which drives the default approval policy. */
enum class ToolKind { Read, Write, Execute, Network }

data class ToolResult(val text: String, val isError: Boolean = false, val changedPath: String? = null)

class ToolContext(
    val ws: Workspace,
    val skills: com.inyogeshwar.agentmt.agent.skills.SkillStore,
    val sessionId: String = "",
    val onTodos: suspend (String) -> Unit = {},
)

interface Tool {
    val name: String
    val description: String
    val schema: JSONObject
    val kind: ToolKind

    /** The thing this call acts on (a path, a command, a URL), used for rules and the approval card. */
    fun target(args: JSONObject): String

    /** One line for the transcript, e.g. "Read app/Main.kt". */
    fun summary(args: JSONObject): String = "$name ${target(args)}".trim()

    /** Optional preview shown on the approval card (diff, file content, command). */
    fun preview(args: JSONObject, ctx: ToolContext): String? = null

    suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult

    fun spec() = ToolSpec(name, description, schema)
}

/** Tiny schema builder. */
fun schema(vararg props: Triple<String, String, String>, required: List<String> = emptyList()): JSONObject =
    JSONObject().put("type", "object").put("properties", JSONObject().apply {
        props.forEach { (n, type, desc) -> put(n, JSONObject().put("type", type).put("description", desc)) }
    }).put("required", JSONArray(required))

fun p(name: String, type: String, desc: String) = Triple(name, type, desc)
