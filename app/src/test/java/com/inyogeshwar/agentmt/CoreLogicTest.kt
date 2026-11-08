package com.inyogeshwar.agentmt

import com.inyogeshwar.agentmt.agent.AgentEngine
import com.inyogeshwar.agentmt.agent.Decision
import com.inyogeshwar.agentmt.agent.PermissionEngine
import com.inyogeshwar.agentmt.agent.llm.parseOpenAiModels
import com.inyogeshwar.agentmt.agent.plugins.CommandStore
import com.inyogeshwar.agentmt.agent.plugins.SlashCommand
import com.inyogeshwar.agentmt.agent.skills.RepoRef
import com.inyogeshwar.agentmt.agent.tools.ToolKind
import com.inyogeshwar.agentmt.agent.tools.countOccurrences
import com.inyogeshwar.agentmt.agent.tools.globToRegex
import com.inyogeshwar.agentmt.agent.tools.unifiedDiff
import com.inyogeshwar.agentmt.data.backup.BackupCrypto
import com.inyogeshwar.agentmt.data.db.MessageEntity
import com.inyogeshwar.agentmt.data.db.RuleEntity
import com.inyogeshwar.agentmt.data.prefs.ApprovalMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test fun glob() {
        assertTrue(globToRegex("**/*.kt").matches("app/src/Main.kt"))
        assertTrue(globToRegex("**/*.kt").matches("Main.kt"))
        assertFalse(globToRegex("src/*.kt").matches("src/a/B.kt"))
        assertTrue(globToRegex("src/{a,b}.ts").matches("src/b.ts"))
    }

    @Test fun diffAndCount() {
        val d = unifiedDiff("a\nb\nc", "a\nB\nc")
        assertTrue(d.contains("- b")); assertTrue(d.contains("+ B"))
        assertEquals(2, countOccurrences("foo bar foo", "foo"))
        assertEquals(0, countOccurrences("abc", ""))
    }

    @Test fun permissions() {
        val rules = listOf(RuleEntity(1, "run_command", "ls *", "allow", 0, 0), RuleEntity(2, "*", "secrets/**", "deny", 1, 0))
        assertEquals(Decision.Allow, PermissionEngine.decide("run_command", ToolKind.Execute, "ls -la", ApprovalMode.AskWrites, rules).first)
        assertEquals(Decision.Ask, PermissionEngine.decide("run_command", ToolKind.Execute, "rm -rf x", ApprovalMode.AskWrites, rules).first)
        assertEquals(Decision.Deny, PermissionEngine.decide("read_file", ToolKind.Read, "secrets/key.txt", ApprovalMode.AskWrites, rules).first)
        assertEquals(Decision.Allow, PermissionEngine.decide("read_file", ToolKind.Read, "a.txt", ApprovalMode.AskWrites, rules).first)
        assertEquals(Decision.Allow, PermissionEngine.decide("edit_file", ToolKind.Write, "a.txt", ApprovalMode.AutoEdits, emptyList()).first)
    }

    @Test fun historyDropsUnansweredToolCallsAndKeepsAttachmentsText() {
        val rows = listOf(
            MessageEntity(1, "s", "user", "hi", createdAt = 0, attachmentsJson = "[]"),
            MessageEntity(2, "s", "assistant", "", toolCallsJson = """[{"id":"t1","name":"read_file","arguments":"{}"},{"id":"t2","name":"glob","arguments":"{}"}]""", createdAt = 0),
            MessageEntity(3, "s", "tool", "content", toolCallId = "t1", toolName = "read_file", status = "ok", createdAt = 0),
        )
        val h = AgentEngine.buildHistory(rows) { _, _ -> "\n<attached_file>x</attached_file>" to emptyList() }
        assertEquals(3, h.size)
        assertTrue(h[0].text.contains("attached_file"))
        assertEquals(listOf("t1"), h[1].toolCalls.map { it.id })
    }

    @Test fun openRouterCatalogCapabilities() {
        val body = """{"data":[{"id":"a/vision","architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools"],"context_length":128000},{"id":"b/text"}]}"""
        val m = parseOpenAiModels(body)
        assertEquals(true, m.first { it.id == "a/vision" }.vision)
        assertEquals(true, m.first { it.id == "a/vision" }.tools)
        assertEquals(null, m.first { it.id == "b/text" }.vision)
    }

    @Test fun repoRefs() {
        assertEquals(RepoRef("anthropics", "skills", "skills/pdf", "main"), RepoRef.parse("https://github.com/anthropics/skills/tree/main/skills/pdf"))
        assertEquals(RepoRef("o", "r", "x/y"), RepoRef.parse("https://skills.sh/o/r/x/y"))
        assertEquals(RepoRef("o", "r"), RepoRef.parse("o/r"))
        assertEquals(null, RepoRef.parse("nope"))
    }

    @Test fun slashExpansion() {
        val c = SlashCommand("x", "", "Fix \$ARGUMENTS in \$1", "t")
        assertEquals("Fix login bug in login", CommandStore.expand(c, "login bug"))
    }

    @Test fun backupCryptoRoundTripAndWrongPass() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), "correct horse")
        assertArrayEquals("secret".toByteArray(), BackupCrypto.decrypt(blob, "correct horse"))
        try { BackupCrypto.decrypt(blob, "wrong"); throw AssertionError("expected") } catch (e: BackupCrypto.WrongPassphrase) { }
    }
}
