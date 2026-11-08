package com.inyogeshwar.agentmt

import com.inyogeshwar.agentmt.agent.Decision
import com.inyogeshwar.agentmt.agent.PermissionEngine
import com.inyogeshwar.agentmt.agent.tools.ToolKind
import com.inyogeshwar.agentmt.core.linux.Distro
import com.inyogeshwar.agentmt.core.linux.LinuxJobs
import com.inyogeshwar.agentmt.core.linux.TarExtractor
import com.inyogeshwar.agentmt.core.linux.ToolSpecs
import com.inyogeshwar.agentmt.data.prefs.ApprovalMode
import com.inyogeshwar.agentmt.feature.browser.UserScriptMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class V05Test {
    private val script = """
// ==UserScript==
// @name         Dark YouTube
// @version      1.2
// @description  Makes things dark
// @match        https://*.youtube.com/*
// @include      /^https://example\.org/a.*/
// @exclude      https://www.youtube.com/embed/*
// @run-at       document-start
// @grant        GM_addStyle
// ==/UserScript==
GM_addStyle('body{background:#000}');
""".trimIndent()

    @Test fun userscriptMetadataAndMatching() {
        val s = UserScriptMeta.parse(script)!!.copy(source = script)
        assertEquals("Dark YouTube", s.name); assertEquals("1.2", s.version); assertEquals("document-start", s.runAt)
        assertTrue(s.appliesTo("https://www.youtube.com/watch?v=1"))
        assertTrue(s.appliesTo("https://m.youtube.com/"))
        assertTrue(s.appliesTo("https://example.org/abc"))
        assertFalse(s.appliesTo("https://www.youtube.com/embed/xyz"))
        assertFalse(s.appliesTo("https://notyoutube.com/"))
        assertFalse(s.appliesTo("http://evil.com/?https://www.youtube.com/"))
        assertTrue(UserScriptMeta.patternMatch("*://*/*", "http://a.b/c"))
        assertTrue(UserScriptMeta.patternMatch("<all_urls>", "https://x.y"))
        assertTrue(UserScriptMeta.wrap(s).contains("GM_addStyle('body{background:#000}')"))
        assertEquals(null, UserScriptMeta.parse("console.log(1)"))
    }

    @Test fun toolSpecsParseAndScripts() {
        val gh = ToolSpecs.parse("github", "https://github.com/Owner/My-Tool.git").getOrThrow()
        assertEquals("my-tool", gh.name); assertEquals("https://github.com/Owner/My-Tool", gh.source); assertEquals("/opt/tools/my-tool", gh.path)
        assertEquals("rea", ToolSpecs.parse("github", "someone/rea").getOrThrow().name)
        assertTrue(ToolSpecs.parse("github", "https://evil.com/a/b").isFailure)
        assertTrue(ToolSpecs.parse("linux", "ffmpeg; rm -rf /").isFailure)
        assertEquals("ffmpeg imagemagick", ToolSpecs.parse("linux", " ffmpeg  imagemagick ").getOrThrow().name)
        assertTrue(ToolSpecs.parse("python", "yt-dlp requests[socks]").isSuccess)
        assertTrue(ToolSpecs.parse("python", "a&&b").isFailure)
        assertTrue(ToolSpecs.installScript(gh, Distro.Ubuntu).contains("git clone --depth 1 'https://github.com/Owner/My-Tool' '/opt/tools/my-tool'"))
        assertTrue(ToolSpecs.installScript(ToolSpecs.parse("linux", "ffmpeg").getOrThrow(), Distro.Alpine).contains("apk add --no-cache ffmpeg"))
        val back = ToolSpecs.parseManifest(ToolSpecs.manifest(listOf(gh)))
        assertEquals(listOf(gh.id), back.map { it.id })
    }

    @Test fun jobTailHandlesCarriageReturns() {
        val t = LinuxJobs.Tail()
        t.feed("start\n#  10%\r## 50%\r"); t.feed("### 100%\ndone\n")
        assertEquals("start\n### 100%\ndone\n", t.text())
    }

    @Test fun autoApproveAll() {
        assertEquals(Decision.Allow, PermissionEngine.decide("run_command", ToolKind.Execute, "rm -rf x", ApprovalMode.AutoAll, emptyList()).first)
        assertEquals(Decision.Ask, PermissionEngine.decide("browser", ToolKind.Network, "https://a", ApprovalMode.AskWrites, emptyList()).first)
    }

    @Test fun paxHeaders() {
        val rec = "30 path=a/very/long/name.txt\n".let { "${it.length} path=a/very/long/name.txt\n" }
        assertEquals("a/very/long/name.txt", TarExtractor.pax(rec.toByteArray())["path"])
    }

    /** Extracts the real rootfs tarballs when the build machine has them (AGENTMT_ROOTFS_DIR). */
    @Test fun extractsRealRootfs() {
        val dir = System.getenv("AGENTMT_ROOTFS_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        for ((file, check) in listOf("ub.tgz" to "usr/bin/apt-get", "alpine.tgz" to "sbin/apk")) {
            val tgz = File(dir, file); if (!tgz.exists()) continue
            val out = Files.createTempDirectory("rootfs").toFile()
            val x = TarExtractor()
            GZIPInputStream(tgz.inputStream().buffered()).use { x.extract(it, out) }
            assertTrue("$file: $check", File(out, check).isFile)
            assertTrue(File(out, check).canExecute())
            assertTrue(Files.isSymbolicLink(File(out, "bin").toPath()) || Files.isSymbolicLink(File(out, "bin/sh").toPath()))
            println("$file: ${x.entries} entries")
            out.deleteRecursively()
        }
    }
}
