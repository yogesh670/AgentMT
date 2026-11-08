package com.inyogeshwar.agentmt.agent

import com.inyogeshwar.agentmt.agent.tools.ToolKind
import com.inyogeshwar.agentmt.agent.tools.globToRegex
import com.inyogeshwar.agentmt.data.db.RuleEntity
import com.inyogeshwar.agentmt.data.prefs.ApprovalMode

enum class Decision { Allow, Ask, Deny }

object PermissionEngine {
    /** First matching rule wins; otherwise the approval mode decides by tool kind. */
    fun decide(tool: String, kind: ToolKind, target: String, mode: ApprovalMode, rules: List<RuleEntity>): Pair<Decision, RuleEntity?> {
        for (r in rules) {
            if (r.tool != "*" && !r.tool.equals(tool, true)) continue
            if (!matches(r.pattern, target, kind)) continue
            return when (r.action) { "allow" -> Decision.Allow; "deny" -> Decision.Deny; else -> Decision.Ask } to r
        }
        val d = when (mode) {
            ApprovalMode.AskAll -> Decision.Ask
            ApprovalMode.AskWrites -> if (kind == ToolKind.Read) Decision.Allow else Decision.Ask
            ApprovalMode.AutoAll -> Decision.Allow
            ApprovalMode.AutoEdits -> if (kind == ToolKind.Read || kind == ToolKind.Write) Decision.Allow else Decision.Ask
        }
        return d to null
    }

    fun matches(pattern: String, target: String, kind: ToolKind): Boolean {
        if (pattern.isBlank() || pattern == "*" || pattern == "**") return true
        return if (kind == ToolKind.Read || kind == ToolKind.Write) globToRegex(pattern).matches(target.removePrefix("./"))
        else Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$").matches(target)
    }
}
