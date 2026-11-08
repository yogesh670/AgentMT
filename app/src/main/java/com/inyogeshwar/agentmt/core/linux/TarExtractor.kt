package com.inyogeshwar.agentmt.core.linux

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Minimal ustar/GNU/pax reader for Linux root filesystems: files, dirs, symlinks, hard links and modes.
 * Pure JVM so it is unit-tested against the real Ubuntu and Alpine tarballs.
 */
class TarExtractor(
    private val chmod: (File, Int) -> Unit = { f, m -> defaultChmod(f, m) },
    private val symlink: (String, File) -> Unit = { target, f -> Files.createSymbolicLink(Paths.get(f.path), Paths.get(target)) },
    /** Leading path components to drop (proot-distro style tarballs wrap everything in one folder). */
    private val strip: Int = 0,
) {
    var entries = 0; private set

    fun extract(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (readFully(input, header) < 512 || header.all { it.toInt() == 0 }) break
            var name = longName ?: paxPath ?: cstr(header, 0, 100).let { n ->
                val magic = cstr(header, 257, 6)
                val prefix = if (magic.startsWith("ustar")) cstr(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            val link = longLink ?: paxLink ?: cstr(header, 157, 100)
            longName = null; longLink = null; paxPath = null; paxLink = null
            val mode = octal(header, 100, 8).toInt()
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> { longName = String(readBytes(input, size)).trimEnd('\u0000'); skipPad(input, size); continue }
                'K' -> { longLink = String(readBytes(input, size)).trimEnd('\u0000'); skipPad(input, size); continue }
                'x' -> { val m = pax(readBytes(input, size)); paxPath = m["path"]; paxLink = m["linkpath"]; skipPad(input, size); continue }
                'g' -> { skip(input, size); skipPad(input, size); continue }
            }
            name = strip(name.removePrefix("./"))
            if (name.isEmpty() || name.split('/').any { it == ".." } || name.startsWith("/")) { skip(input, size); skipPad(input, size); continue }
            val out = File(dest, name)
            entries++
            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode or 0b111000000) }
                '2' -> { out.parentFile?.mkdirs(); out.delete(); runCatching { symlink(link, out) } }
                '1' -> {
                    out.parentFile?.mkdirs()
                    val src = File(dest, strip(link.removePrefix("./")))
                    runCatching { src.copyTo(out, true); chmod(out, mode) }
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (Files.isSymbolicLink(out.toPath())) out.delete()
                    out.outputStream().use { o ->
                        var left = size; val buf = ByteArray(64 * 1024)
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }
                    }
                    chmod(out, mode)
                }
                else -> skip(input, size) // devices and fifos can't be created without root; proot binds /dev instead
            }
            skipPad(input, size)
        }
    }

    private fun strip(path: String): String {
        if (strip == 0) return path
        val parts = path.split('/')
        return if (parts.size <= strip) "" else parts.drop(strip).joinToString("/")
    }

    companion object {
        fun defaultChmod(f: File, mode: Int) {
            runCatching {
                val perms = HashSet<java.nio.file.attribute.PosixFilePermission>()
                val all = java.nio.file.attribute.PosixFilePermission.values()
                // OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_*, OTHERS_* in bit order 8..0
                all.forEachIndexed { i, p -> if (mode and (1 shl (8 - i)) != 0) perms += p }
                Files.setPosixFilePermissions(f.toPath(), perms)
            }.onFailure { if (mode and 0b001000000 != 0) f.setExecutable(true, false) }
        }

        fun pax(b: ByteArray): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < b.size) {
                var sp = i; while (sp < b.size && b[sp] != ' '.code.toByte()) sp++
                val len = String(b, i, sp - i).toIntOrNull() ?: break
                if (len <= 0 || i + len > b.size) break
                val rec = String(b, sp + 1, i + len - sp - 2, Charsets.UTF_8)
                val eq = rec.indexOf('=')
                if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
                i += len
            }
            return out
        }

        private fun cstr(b: ByteArray, off: Int, len: Int): String { var e = off; while (e < off + len && b[e].toInt() != 0) e++; return String(b, off, e - off) }
        private fun octal(b: ByteArray, off: Int, len: Int): Long {
            if (b[off].toInt() and 0x80 != 0) { // GNU base-256 for big files
                var v = 0L; for (k in off + 1 until off + len) v = (v shl 8) or (b[k].toLong() and 0xff); return v
            }
            return cstr(b, off, len).trim().ifEmpty { "0" }.toLong(8)
        }
        private fun readFully(i: InputStream, b: ByteArray): Int { var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }; return n }
        private fun readBytes(i: InputStream, size: Long) = ByteArray(size.toInt()).also { readFully(i, it) }
        private fun skip(i: InputStream, size: Long) { var left = size; val buf = ByteArray(8192); while (left > 0) { val r = i.read(buf, 0, minOf(8192L, left).toInt()); if (r < 0) break; left -= r } }
        private fun skipPad(i: InputStream, size: Long) { val pad = (512 - size % 512) % 512; skip(i, pad) }
    }
}
