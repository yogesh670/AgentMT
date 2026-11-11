package com.inyogeshwar.agentmt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inyogeshwar.agentmt.agent.tools.PatchTool
import com.inyogeshwar.agentmt.agent.skills.SkillStore
import com.inyogeshwar.agentmt.agent.tools.ToolContext
import com.inyogeshwar.agentmt.agent.tools.Workspace
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PatchToolTest {
    private val tool = PatchTool()
    private val root = File(java.io.File(System.getProperty("java.io.tmpdir")), "agentmt-patch-test-${System.nanoTime()}").apply { delete(); mkdirs() }
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val toolContext = ToolContext(Workspace(root), SkillStore(ctx))

    private fun runPatch(patch: String): String = runBlocking {
        tool.run(JSONObject().put("patchText", patch), toolContext).text
    }

    private fun read(p: String): String = File(root, p).readText()

    @Test fun addFileAndUpdateIt() {
        runPatch("""
*** Begin Patch
*** Add File: hello.txt
+Hello world
*** Update File: hello.txt
@@ greeting
-Hello world
+Hello, AgentMT!
*** End Patch
""".trimIndent())
        assertEquals("Hello, AgentMT!\n", read("hello.txt"))
    }

    @Test fun addAndDeleteFile() {
        runPatch("""
*** Begin Patch
*** Add File: tmp.txt
+temporary
*** Delete File: tmp.txt
*** End Patch
""".trimIndent())
        assertEquals(false, File(root, "tmp.txt").exists())
    }

    @Test fun updateWithMove() {
        runPatch("""
*** Begin Patch
*** Add File: old.txt
+keep me
*** Update File: old.txt
*** Move to: new.txt
@@
 keep me
+and me
*** End Patch
""".trimIndent())
        assertEquals(false, File(root, "old.txt").exists())
        assertEquals("keep me\nand me\n", read("new.txt"))
    }

    @Test fun badHunkFailsWholePatch() {
        File(root, "a.txt").writeText("line one\ntarget line\nline three\n")
        val result = runPatch("""
*** Begin Patch
*** Update File: a.txt
@@
-target line
+changed line
-nothing here
+never applied
*** End Patch
""".trimIndent())
        assertEquals(false, result.contains("Applied patch"))
        assertEquals("line one\ntarget line\nline three\n", read("a.txt"))
    }
}
