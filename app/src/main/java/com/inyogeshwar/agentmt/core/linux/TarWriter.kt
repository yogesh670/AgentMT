package com.inyogeshwar.agentmt.core.linux

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Minimal ustar writer with pax headers for long paths, for backing up a Linux root filesystem.
 * Writes files, dirs and symlinks with their modes; [TarExtractor] reads its output back.
 */
class TarWriter(
    private val out: OutputStream,
    /** Permission bits of a file (not following symlinks). */
    private val mode: (File) -> Int = ::defaultMode,
) {
    var entries = 0; private set
    var bytes = 0L; private set

    /** Adds everything under [root] (not [root] itself), skipping guest paths for which [skip] is true ("proc", "tmp/x"). */
    fun addTree(root: File, skip: (String) -> Boolean = { false }, onEntry: (String) -> Unit = {}) {
        fun walk(dir: File, rel: String) {
            val kids = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in kids) {
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (skip(r)) continue
                val p = f.toPath()
                when {
                    Files.isSymbolicLink(p) -> entry(r, '2', mode(f), 0, Files.readSymbolicLink(p).toString(), null)
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> { entry("$r/", '5', mode(f), 0, "", null); walk(f, r) }
                    Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) -> entry(r, '0', mode(f), f.length(), "", f)
                    else -> {} // sockets, fifos, devices: not needed in a backup
                }
                onEntry(r)
            }
        }
        walk(root, "")
    }

    fun finish() { out.write(ByteArray(1024)); out.flush() }

    private fun entry(name: String, type: Char, mode: Int, size: Long, link: String, file: File?) {
        val path = "./$name"
        if (path.toByteArray().size > 99 || link.toByteArray().size > 99) {
            val pax = StringBuilder()
            if (path.toByteArray().size > 99) pax.append(paxRecord("path", path))
            if (link.toByteArray().size > 99) pax.append(paxRecord("linkpath", link))
            val data = pax.toString().toByteArray()
            header("./PaxHeaders/x", 'x', 420, data.size.toLong(), "")
            out.write(data); pad(data.size.toLong())
        }
        header(path, type, mode, size, link)
        if (file != null) {
            var n = 0L
            file.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val k = input.read(buf); if (k < 0) break; out.write(buf, 0, k); n += k } }
            require(n == size) { "File changed during backup: $name" }
            pad(size)
        }
        entries++; bytes += size
    }

    private fun header(name: String, type: Char, mode: Int, size: Long, link: String) {
        val h = ByteArray(512)
        put(h, 0, 100, name.toByteArray().copyOf(minOf(100, name.toByteArray().size)))
        octal(h, 100, 8, mode.toLong() and 0xFFF)
        octal(h, 108, 8, 0); octal(h, 116, 8, 0)
        octal(h, 124, 12, size)
        octal(h, 136, 12, System.currentTimeMillis() / 1000)
        h[156] = type.code.toByte()
        put(h, 157, 100, link.toByteArray().copyOf(minOf(100, link.toByteArray().size)))
        put(h, 257, 6, "ustar\u0000".toByteArray()); put(h, 263, 2, "00".toByteArray())
        put(h, 265, 32, "root".toByteArray()); put(h, 297, 32, "root".toByteArray())
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(h, 148, 8, ("%06o".format(sum) + "\u0000 ").toByteArray())
        out.write(h)
    }

    private fun pad(size: Long) { val r = (512 - (size % 512)) % 512; if (r > 0) out.write(ByteArray(r.toInt())) }
    private fun put(h: ByteArray, off: Int, len: Int, b: ByteArray) = System.arraycopy(b, 0, h, off, minOf(len, b.size))
    private fun octal(h: ByteArray, off: Int, len: Int, v: Long) = put(h, off, len, ("%0${len - 1}o".format(v) + "\u0000").toByteArray())

    companion object {
        /** A pax record is "<len> key=value\n" where len counts its own digits. */
        fun paxRecord(key: String, value: String): String {
            val body = " $key=$value\n"; val bl = body.toByteArray().size
            var len = bl + 1
            while (len.toString().length + bl != len) len = len.toString().length + bl
            return "$len$body"
        }

        fun defaultMode(f: File): Int = runCatching {
            val perms = Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS)
            var m = 0
            PosixFilePermission.values().forEachIndexed { i, p -> if (p in perms) m = m or (1 shl (8 - i)) }
            m
        }.getOrElse { if (f.isDirectory || f.canExecute()) 493 else 420 }
    }
}
