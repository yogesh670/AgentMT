package com.inyogeshwar.agentmt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inyogeshwar.agentmt.agent.tools.DiagnosticsTool
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.agent.tools.ToolContext
import com.inyogeshwar.agentmt.agent.tools.Workspace
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiagnosticsTest {
    private val tool = DiagnosticsTool()
    private val root = File(java.io.File(System.getProperty("java.io.tmpdir")), "agentmt-diag-test-${System.nanoTime()}").apply { delete(); mkdirs() }
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val toolContext = ToolContext(Workspace(root), SkillStore(ctx))

    private fun run(paths: List<String>): String = runBlocking {
        tool.run(JSONObject().put("paths", JSONArray(paths)), toolContext).text
    }

    private fun write(name: String, text: String): String {
        val f = File(root, name)
        f.parentFile.mkdirs()
        f.writeText(text)
        return name
    }

    @Test fun validJsonIsOk() {
        val p = write("a.json", """{"a": 1}""")
        assertTrue(run(listOf(p)).contains("OK  $p"))
    }

    @Test fun badJsonReportsIssue() {
        val p = write("b.json", """{"a": """)
        val out = run(listOf(p))
        assertTrue(out.contains("ISSUES  $p"))
    }

    @Test fun validXmlIsOk() {
        val p = write("c.xml", "<x/>")
        assertTrue(run(listOf(p)).contains("OK  $p"))
    }

    @Test fun unbalancedBracketsReports() {
        val p = write("d.kt", """fun f() {\n  println("hi"\n}""")
        val out = run(listOf(p))
        assertTrue(out.contains("ISSUES  $p"))
        assertTrue(out.contains("Unmatched") || out.contains("Unclosed"))
    }

    @Test fun unterminatedStringReports() {
        val p = write("e.kt", """val s = "abc""")
        val out = run(listOf(p))
        assertTrue(out.contains("ISSUES  $p"))
        assertTrue(out.contains("Unterminated"))
    }

    @Test fun notFoundReports() {
        assertTrue(run(listOf("nope.kt")).contains("nope.kt: not found"))
    }
}
