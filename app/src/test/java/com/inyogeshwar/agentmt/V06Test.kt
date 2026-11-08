package com.inyogeshwar.agentmt

import com.inyogeshwar.agentmt.core.linux.AndroidBridge
import com.inyogeshwar.agentmt.core.linux.TarExtractor
import com.inyogeshwar.agentmt.core.linux.TarWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class V06Test {
    @Test fun paxRecordLengthCountsItself() {
        listOf("a", "x".repeat(90), "y".repeat(995)).forEach { v ->
            val r = TarWriter.paxRecord("path", v)
            assertEquals(r.toByteArray().size, r.substringBefore(' ').toInt())
        }
    }

    @Test fun tarRoundTripKeepsFilesLinksModesAndLongNames() {
        val src = Files.createTempDirectory("rt-src").toFile()
        val deep = File(src, "usr/" + "d".repeat(60) + "/" + "e".repeat(60)).apply { mkdirs() }
        File(deep, "long-file-name.txt").writeText("hello long")
        File(src, "bin").mkdirs()
        File(src, "bin/tool").apply { writeText("#!/bin/sh\necho hi\n"); setExecutable(true) }
        Files.createSymbolicLink(Paths.get(src.path, "bin/link"), Paths.get("tool"))
        Files.createSymbolicLink(Paths.get(src.path, "longlink"), Paths.get("usr/" + "d".repeat(60) + "/" + "e".repeat(60) + "/long-file-name.txt"))
        File(src, "proc/self").mkdirs(); File(src, "proc/self/x").writeText("skip me")
        File(src, "empty").mkdirs()
        val big = ByteArray(700_000) { (it % 251).toByte() }; File(src, "big.bin").writeBytes(big)

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> TarWriter(gz).apply { addTree(src, skip = { it.startsWith("proc/") }); finish() } }

        val dst = Files.createTempDirectory("rt-dst").toFile()
        GZIPInputStream(ByteArrayInputStream(bos.toByteArray())).use { TarExtractor().extract(it, dst) }
        assertEquals("hello long", File(dst, "usr/" + "d".repeat(60) + "/" + "e".repeat(60) + "/long-file-name.txt").readText())
        assertEquals("tool", Files.readSymbolicLink(Paths.get(dst.path, "bin/link")).toString())
        assertTrue(Files.isSymbolicLink(Paths.get(dst.path, "longlink")))
        assertEquals("hello long", File(dst, "longlink").readText())
        assertTrue(File(dst, "bin/tool").canExecute())
        assertTrue(File(dst, "proc").isDirectory)
        assertTrue(!File(dst, "proc/self").exists())
        assertTrue(File(dst, "empty").isDirectory)
        assertTrue(big.contentEquals(File(dst, "big.bin").readBytes()))
    }

    @Test fun bridgeScriptsAreValidShell() {
        val dir = Files.createTempDirectory("bridge").toFile()
        AndroidBridge.scripts().forEach { (name, body) ->
            assertTrue(name, body.startsWith("#!/bin/sh\n"))
            val f = File(dir, name).apply { writeText(body) }
            val sh = listOf("/bin/dash", "/bin/sh").firstOrNull { File(it).exists() } ?: return
            val p = ProcessBuilder(sh, "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name: $out", 0, p.waitFor())
        }
    }

    /** Runs agentmt-bridge against a fake app that answers requests, end to end through the files. */
    @Test fun bridgeScriptRequestResponse() {
        val sh = File("/bin/sh").takeIf { it.exists() } ?: return
        val dir = Files.createTempDirectory("b").toFile()
        val script = AndroidBridge.scripts()["agentmt-bridge"]!!.replace("B=/var/lib/agentmt-bridge", "B=${dir.path}")
        val f = File(dir.parentFile, "agentmt-bridge-test-${System.nanoTime()}").apply { writeText(script) }
        val answerer = Thread {
            val end = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < end) {
                dir.listFiles { x -> x.name.endsWith(".req") }?.forEach { req ->
                    val lines = req.readLines(); req.delete()
                    val arg = String(java.util.Base64.getDecoder().decode(lines[1]))
                    File(dir, req.name.removeSuffix(".req") + ".resp").writeText("got ${lines[0]}:$arg")
                    return@Thread
                }
                Thread.sleep(20)
            }
        }.apply { start() }
        val p = ProcessBuilder(sh.path, f.path, "clipboard-set", "héllo", "wörld").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(0, p.waitFor()); answerer.join()
        assertEquals("got clipboard-set:héllo wörld", out)
        f.delete()
    }
}

class V07Test {
    private fun m(id: Long, role: String, text: String, calls: String? = null, callId: String? = null) =
        com.inyogeshwar.agentmt.data.db.MessageEntity(id = id, sessionId = "s", role = role, text = text, toolCallsJson = calls, toolCallId = callId, createdAt = id)

    @Test fun noCompactionUnderBudget() {
        val rows = listOf(m(1, "user", "hi"), m(2, "assistant", "hello"))
        org.junit.Assert.assertNull(com.inyogeshwar.agentmt.agent.AgentEngine.compactionCut(rows, 10_000))
    }

    @Test fun compactionCutNeverSplitsToolPairs() {
        val rows = ArrayList<com.inyogeshwar.agentmt.data.db.MessageEntity>()
        rows += m(1, "user", "clone this app")
        var id = 2L
        repeat(30) { i ->
            rows += m(id++, "assistant", "step $i", calls = """[{"id":"c$i","name":"run_command","arguments":"{}"}]""")
            rows += m(id++, "tool", "x".repeat(3000), callId = "c$i")
        }
        val cut = com.inyogeshwar.agentmt.agent.AgentEngine.compactionCut(rows, 30_000)!!
        val first = rows.first { it.id == cut }
        org.junit.Assert.assertNotEquals("tool", first.role)
        org.junit.Assert.assertTrue(rows.filter { it.id >= cut }.sumOf { it.text.length } <= 30_000)

        // With a summary row, history starts with the summary and only the kept tail follows, tool pairs intact.
        val withSummary = rows + m(id, "summary", "SUMMARY TEXT", callId = cut.toString())
        val h = com.inyogeshwar.agentmt.agent.AgentEngine.buildHistory(withSummary)
        org.junit.Assert.assertTrue(h.first().text.contains("SUMMARY TEXT"))
        val keptCalls = h.flatMap { it.toolCalls }.map { it.id }.toSet()
        val keptResults = h.filter { it.role == "tool" }.map { it.toolCallId }.toSet()
        org.junit.Assert.assertEquals(keptCalls, keptResults)
        org.junit.Assert.assertNull(com.inyogeshwar.agentmt.agent.AgentEngine.compactionCut(withSummary, 30_000))
    }
}

class V08Test {
    @Test fun npmSpecsParse() {
        val t = com.inyogeshwar.agentmt.core.linux.ToolSpecs.parse("npm", "rea-agents @playwright/test@1.49.1").getOrThrow()
        org.junit.Assert.assertEquals("npm", t.kind)
        org.junit.Assert.assertTrue(com.inyogeshwar.agentmt.core.linux.ToolSpecs.parse("npm", "rm -rf /;").isFailure)
        val script = com.inyogeshwar.agentmt.core.linux.ToolSpecs.installScript(t, com.inyogeshwar.agentmt.core.linux.Distro.Ubuntu)
        org.junit.Assert.assertTrue(script.contains("npm install -g rea-agents @playwright/test@1.49.1"))
        org.junit.Assert.assertTrue(script.contains("latest-v22.x"))
    }

    @Test fun kitScriptsAreValidShell() {
        com.inyogeshwar.agentmt.core.linux.ToolSpecs.KITS.forEach { k ->
            val f = java.io.File.createTempFile("kit", ".sh")
            f.writeText(com.inyogeshwar.agentmt.core.linux.ToolSpecs.kitScript(k, com.inyogeshwar.agentmt.core.linux.Distro.Ubuntu))
            val sh = listOf("/bin/dash", "/bin/sh").first { java.io.File(it).exists() }
            val p = ProcessBuilder(sh, "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            org.junit.Assert.assertEquals("${k.title}: $out", 0, p.waitFor())
        }
    }

    @Test fun stdioMcpTalksJsonRpc() {
        // A tiny line-based server: replies to initialize and tools/list, pings us first.
        val server = """
while IFS= read -r line; do
  id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  case "${'$'}line" in
    *'"method":"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"serverInfo":{"name":"fake"}}}\n' "${'$'}id";;
    *'"method":"tools/list"'*) echo 'log noise'; printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"t1","description":"d"}]}}\n' "${'$'}id";;
  esac
done
""".trim()
        val p = ProcessBuilder("/bin/sh", "-c", server).start()
        val w = p.outputStream.bufferedWriter(); val r = p.inputStream.bufferedReader()
        w.write("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""); w.newLine(); w.flush()
        org.junit.Assert.assertTrue(r.readLine().contains("fake"))
        w.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""); w.newLine(); w.flush()
        org.junit.Assert.assertEquals("log noise", r.readLine())
        org.junit.Assert.assertTrue(r.readLine().contains("\"t1\""))
        p.destroy()
    }
}

class V081Test {
    @Test fun catalogParses() {
        val f = listOf("src/main/assets/catalog/providers.json", "app/src/main/assets/catalog/providers.json").map { java.io.File(it) }.first { it.exists() }
        val cat = com.inyogeshwar.agentmt.agent.ProviderCatalog.parse(f.readText())
        org.junit.Assert.assertTrue(cat.size > 200)
        val sonnet = cat.first { it.id == "anthropic" }.models.first { it.id == "claude-sonnet-4-5" }
        org.junit.Assert.assertEquals(200000, sonnet.context)
        org.junit.Assert.assertTrue(sonnet.tools)
        org.junit.Assert.assertEquals("\$3/\$15 per M", sonnet.priceLabel())
    }
}

class V09Test {
    @Test fun termuxQuotingAndVisibility() {
        val t = com.inyogeshwar.agentmt.core.termux.TermuxBridge
        org.junit.Assert.assertEquals("'it'\\''s'", t.shellQuote("it's"))
        org.junit.Assert.assertTrue(t.canSee("/storage/emulated/0/MT2/VibeCoded"))
        org.junit.Assert.assertFalse(t.canSee("/data/user/0/com.inyogeshwar.agentmt/files/workspace"))
        val p = com.inyogeshwar.agentmt.agent.mcp.TermuxMcp.port("rea mcp")
        org.junit.Assert.assertTrue(p in 18100 until 18900)
    }

    @Test fun continueKeepsFinishedToolSteps() {
        val rows = listOf(
            com.inyogeshwar.agentmt.data.db.MessageEntity(id = 1, sessionId = "s", role = "user", text = "build it", createdAt = 1),
            com.inyogeshwar.agentmt.data.db.MessageEntity(id = 2, sessionId = "s", role = "assistant", text = "",
                toolCallsJson = """[{"id":"a","name":"run_command","arguments":"{}"},{"id":"b","name":"run_command","arguments":"{}"}]""", createdAt = 2),
            com.inyogeshwar.agentmt.data.db.MessageEntity(id = 3, sessionId = "s", role = "tool", text = "ok", toolCallId = "a", toolName = "run_command", status = "ok", createdAt = 3),
            com.inyogeshwar.agentmt.data.db.MessageEntity(id = 4, sessionId = "s", role = "user", text = com.inyogeshwar.agentmt.agent.AgentEngine.CONTINUE_TEXT, createdAt = 4),
        )
        val h = com.inyogeshwar.agentmt.agent.AgentEngine.buildHistory(rows)
        org.junit.Assert.assertEquals(listOf("user", "assistant", "tool", "user"), h.map { it.role })
        org.junit.Assert.assertEquals(listOf("a"), h[1].toolCalls.map { it.id }) // the unanswered call "b" is dropped
    }
}
