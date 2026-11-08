package com.inyogeshwar.agentmt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every bundled native library must find its NEEDED libraries either in the APK or on Android itself.
 * v0.4.0–v0.9.1 shipped libproot.so without libandroid-shmem.so, so Linux never started on phones.
 */
class NativeLibsTest {
    private val system = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libstdc++.so")

    private fun needed(f: File): List<String> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 0x464c457f || b.get(4).toInt() != 2) return emptyList() // ELF64 only
        val shoff = b.getLong(0x28); val shentsize = b.getShort(0x3A).toInt(); val shnum = b.getShort(0x3C).toInt()
        data class Sec(val type: Int, val off: Long, val size: Long, val link: Int)
        val secs = (0 until shnum).map { i -> val o = (shoff + i * shentsize).toInt()
            Sec(b.getInt(o + 4), b.getLong(o + 0x18), b.getLong(o + 0x20), b.getInt(o + 0x28)) }
        val dyn = secs.firstOrNull { it.type == 6 } ?: return emptyList()
        val str = secs[dyn.link]
        val out = ArrayList<String>()
        var o = dyn.off.toInt()
        while (o < dyn.off + dyn.size) {
            val tag = b.getLong(o); val v = b.getLong(o + 8); o += 16
            if (tag == 0L) break
            if (tag == 1L) { var p = (str.off + v).toInt(); val sb = StringBuilder(); while (b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar()); out += sb.toString() }
        }
        return out
    }

    @Test fun bundledLibsHaveTheirDependencies() {
        val root = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val dirs = listOf("main", "full").map { File(root, "$it/jniLibs/arm64-v8a") }.filter { it.isDirectory }
        val libs = dirs.flatMap { it.listFiles().orEmpty().toList() }.filter { it.name.endsWith(".so") }
        assertTrue("no native libs found", libs.isNotEmpty())
        val names = libs.map { it.name }.toSet()
        val missing = libs.flatMap { l -> needed(l).filter { it !in names && it !in system }.map { "${l.name} needs $it" } }
        assertTrue("Missing: $missing", missing.isEmpty())
    }
}
