package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.agent.mcp.McpClient
import com.inyogeshwar.agentmt.agent.mcp.McpToolDef
import org.json.JSONObject

/** Exposes one remote MCP tool to the model as mcp__<server>__<tool>. */
class McpTool(serverSlug: String, private val serverName: String, private val def: McpToolDef, private val client: McpClient) : Tool {
    override val name = "mcp__${serverSlug}__${def.name}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    override val description = "[$serverName] ${def.description}".take(1024)
    override val schema: JSONObject = def.inputSchema
    override val kind = ToolKind.Network
    override fun target(args: JSONObject) = "$serverName/${def.name}"
    override fun summary(args: JSONObject) = "Called ${def.name} on $serverName"
    override fun preview(args: JSONObject, ctx: ToolContext) = args.toString(2).take(2000)
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val (text, err) = client.callTool(def.name, args)
        return ToolResult(text.take(60_000), err)
    }
}
