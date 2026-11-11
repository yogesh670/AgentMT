package com.inyogeshwar.agentmt.agent.tools

import org.json.JSONObject

/**
 * Pauses the run and asks the user for a decision or missing detail. The answer is returned as the
 * tool result and echoed into the chat so it stays visible after the question card disappears.
 */
class QuestionTool : Tool {
    override val name = "ask_user"
    override val kind = ToolKind.Read
    override val description = "Ask the user one or more questions when you need a decision or missing information. " +
        "Provide short options when possible; the user can also type their own answer. Do not use this for things " +
        "you can find out by reading files or running a command."
    override val schema: JSONObject = JSONObject(
        """{"type":"object","properties":{"question":{"type":"string","description":"The question to ask"},"options":{"type":"array","description":"Short suggested answers","items":{"type":"string"}}},"required":["question"]}""",
    )

    override fun target(args: JSONObject) = args.optString("question").take(120).ifBlank { "question" }
    override fun summary(args: JSONObject) = "Asked: ${args.optString("question").take(80)}"

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return ToolResult("question is required", true)
        val options = args.optJSONArray("options")?.let { a ->
            (0 until minOf(a.length(), 6)).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        }.orEmpty()
        val answer = ctx.onAsk(question, options)
            ?: return ToolResult("The user stopped before answering.", true)
        if (answer.isBlank()) return ToolResult("The user skipped the question. Continue without that detail.", true)
        return ToolResult("User answered: $answer", echo = answer)
    }
}
