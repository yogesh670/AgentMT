package com.inyogeshwar.agentmt.core.linux

import android.content.Context
import android.os.Build
import android.system.Os
import com.inyogeshwar.agentmt.agent.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Root filesystems AgentMT can run. Both are official upstream tarballs, checked by SHA-256. */
enum class Distro(
    val id: String, val label: String, val version: String, val url: String, val sha256: String,
    val downloadMb: Int, val pkg: String, val shell: String,
) {
    Ubuntu("ubuntu", "Ubuntu", "24.04.5 LTS",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", 29, "apt", "/bin/bash"),
    Alpine("alpine", "Alpine Linux", "3.24.4",
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.4-aarch64.tar.gz",
        "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de", 4, "apk", "/bin/sh");

    val installHint: String get() = if (this == Ubuntu) "apt install <pkg>" else "apk add <pkg>"
    fun installCmd(pkgs: String) = if (this == Ubuntu) "DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgs || (apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgs)" else "apk add $pkgs"
    fun removeCmd(pkgs: String) = if (this == Ubuntu) "apt-get remove -y $pkgs" else "apk del $pkgs"
}

/**
 * A real Linux userland (Ubuntu or Alpine) that runs inside the app through proot, like Termux's proot-distro.
 * proot, its loader and libtalloc ship as native libraries (the only place Android 10+ lets an app execute its
 * own binaries). Only arm64 phones get Linux; every phone keeps the Android shell.
 */
object LinuxEnv {
    sealed interface State {
        data object Unsupported : State
        data object NotInstalled : State
        data class Installing(val distro: Distro, val step: String, val progress: Float?, val speed: String = "") : State
        data class Ready(val distro: Distro) : State
        data class Failed(val message: String) : State
    }

    private lateinit var app: Context
    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state

    fun init(context: Context) {
        app = context.applicationContext
        refresh()
    }

    private fun refresh() {
        _state.value = when {
            !supported -> State.Unsupported
            else -> active?.let { State.Ready(it) } ?: State.NotInstalled
        }
    }

    val supported: Boolean get() = com.inyogeshwar.agentmt.BuildConfig.FULL && runCatching { Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && File(nativeDir, "libproot.so").exists() && File(nativeDir, "libandroid-shmem.so").exists() }.getOrDefault(false)
    val ready: Boolean get() = state.value is State.Ready
    val distro: Distro? get() = (state.value as? State.Ready)?.distro
    private val nativeDir: File get() = File(app.applicationInfo.nativeLibraryDir)
    private val base: File get() = File(app.filesDir, "linux")
    fun rootOf(d: Distro) = File(base, d.id)
    fun installed(d: Distro) = File(rootOf(d), ".agentmt-ready").exists()
    fun installedDistros() = Distro.entries.filter { installed(it) }

    /** The distro commands run in. Ubuntu wins when both are installed unless the user picked one. */
    val active: Distro? get() {
        val picked = runCatching { File(base, "active").readText().trim() }.getOrNull()
        return Distro.entries.firstOrNull { it.id == picked && installed(it) } ?: installedDistros().firstOrNull()
    }
    fun setActive(d: Distro) { if (installed(d)) { base.mkdirs(); File(base, "active").writeText(d.id); refresh() } }

    val rootfs: File get() = rootOf(active ?: Distro.Ubuntu)
    private val tmp: File get() = File(app.cacheDir, "proot-tmp").apply { mkdirs() }

    suspend fun install(d: Distro = Distro.Ubuntu) = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        runCatching {
            val label = "${d.label} ${d.version}"
            _state.value = State.Installing(d, "Downloading $label", 0f)
            val tgz = File(app.cacheDir, "${d.id}.tar.gz")
            Http.client.newCall(Request.Builder().url(d.url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
                val total = r.body!!.contentLength().takeIf { it > 0 }
                val md = MessageDigest.getInstance("SHA-256")
                val started = System.currentTimeMillis()
                r.body!!.byteStream().use { input -> tgz.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var lastUi = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 250) {
                            lastUi = now
                            val secs = ((now - started) / 1000.0).coerceAtLeast(0.1)
                            _state.value = State.Installing(d, "Downloading $label", total?.let { done.toFloat() / it },
                                "%.1f MB/s".format(done / 1_048_576.0 / secs))
                        }
                    }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != d.sha256) { tgz.delete(); error("Download was corrupted (checksum mismatch)") }
            }
            _state.value = State.Installing(d, "Unpacking $label", null)
            val root = rootOf(d)
            root.deleteRecursively(); root.mkdirs()
            val tar = TarExtractor(chmod = { f, m -> runCatching { Os.chmod(f.path, m and 0xFFF) } }, symlink = { t, f -> Os.symlink(t, f.path) })
            GZIPInputStream(tgz.inputStream().buffered(256 * 1024)).use { tar.extract(it, root) }
            tgz.delete()
            _state.value = State.Installing(d, "Configuring $label", null)
            configure(d, root)
            File(root, ".agentmt-ready").writeText(d.version)
            base.mkdirs(); File(base, "active").writeText(d.id)
            refresh()
        }.onFailure { _state.value = State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun configure(d: Distro, root: File) {
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "etc/hosts").apply { delete(); writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n") }
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/agentmt.sh").writeText(
            "export PS1='\\[\\e[1;32m\\]\\u@agentmt\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
            "export LANG=C.UTF-8\n" +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/gradle/bin\n" +
            "[ -d /opt/tools ] && for d in /opt/tools/*/bin; do [ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done\n")
        listOf("root", "tmp", "opt/tools").forEach { File(root, it).mkdirs() }
        runCatching { Os.chmod(File(root, "tmp").path, "1777".toInt(8)) }
        if (d == Distro.Ubuntu) {
            File(root, "etc/apt/apt.conf.d").mkdirs()
            // proot can't drop privileges to the _apt user, and a pty for dpkg just adds noise in logs.
            File(root, "etc/apt/apt.conf.d/99agentmt").writeText(
                "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"0\";\nAPT::Install-Recommends \"false\";\n")
            File(root, "root/.bashrc").takeIf { !it.exists() }?.writeText("[ -f /etc/profile.d/agentmt.sh ] && . /etc/profile.d/agentmt.sh\n")
        }
        writeFakeProc()
        ensureHelpers(root)
    }

    /** Installs the agentmt-* commands (open, share, clipboard, notify, toast) that talk to Android. */
    private fun ensureHelpers(root: File) {
        val bin = File(root, "usr/local/bin").apply { mkdirs() }
        val stamp = File(bin, ".agentmt-helpers")
        if (stamp.exists() && stamp.readText() == AndroidBridge.VERSION) return
        AndroidBridge.scripts().forEach { (name, body) ->
            File(bin, name).apply { writeText(body); runCatching { Os.chmod(path, "755".toInt(8)) } }
        }
        File(root, "var/lib/agentmt-bridge").mkdirs()
        stamp.writeText(AndroidBridge.VERSION)
    }

    /** Paths (relative to the rootfs) a backup leaves out: kernel mounts, temp files, the Android bridge. */
    private fun skipInBackup(rel: String) = (rel.contains('/') && rel.substringBefore('/') in setOf("proc", "sys", "dev", "storage", "sdcard", "data", "tmp")) ||
        rel.startsWith("var/lib/agentmt-bridge/") || rel.startsWith("var/cache/apt/archives/") && rel.endsWith(".deb") || rel == ".agentmt-ready"

    /** Writes [d]'s whole Linux (installed packages, /root, /opt/tools) as a .tar.gz, like proot-distro backup. */
    suspend fun backup(d: Distro, out: java.io.OutputStream, progress: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        require(installed(d)) { "${d.label} is not installed" }
        java.util.zip.GZIPOutputStream(out.buffered(256 * 1024), 64 * 1024).use { gz ->
            val w = TarWriter(gz, mode = { f -> runCatching { Os.lstat(f.path).st_mode and 0xFFF }.getOrElse { TarWriter.defaultMode(f) } })
            File(rootOf(d), ".agentmt-distro").writeText(d.id + "\n")
            var last = 0L
            w.addTree(rootOf(d), ::skipInBackup) { rel ->
                val now = System.currentTimeMillis(); if (now - last > 300) { last = now; progress("${w.entries} files · ${w.bytes / 1_048_576} MB") }
            }
            w.finish(); w.entries
        }
    }

    /** Restores a backup made by [backup] (or any rootfs .tar.gz with /etc/os-release), replacing that distro. */
    suspend fun restore(input: java.io.InputStream, progress: (String) -> Unit = {}): Distro = withContext(Dispatchers.IO) {
        val staging = File(base, "restore-tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val tar = TarExtractor(chmod = { f, m -> runCatching { Os.chmod(f.path, m and 0xFFF) } }, symlink = { t, f -> Os.symlink(t, f.path) })
            progress("Unpacking backup")
            GZIPInputStream(input.buffered(256 * 1024)).use { tar.extract(it, staging) }
            val id = File(staging, ".agentmt-distro").takeIf { it.exists() }?.readText()?.trim()
                ?: runCatching { File(staging, "etc/os-release").readLines().first { it.startsWith("ID=") }.substringAfter("=").trim('"') }.getOrNull()
            val d = Distro.entries.firstOrNull { it.id == id } ?: error("Not an Ubuntu or Alpine backup")
            progress("Restoring ${d.label}")
            rootOf(d).deleteRecursively()
            if (!staging.renameTo(rootOf(d))) error("Could not move the restored files into place")
            configure(d, rootOf(d))
            File(rootOf(d), ".agentmt-ready").writeText(d.version)
            File(base, "active").writeText(d.id)
            refresh(); d
        } finally { staging.deleteRecursively() }
    }

    fun uninstall(d: Distro) {
        rootOf(d).deleteRecursively()
        refresh()
    }

    /** Environment for proot itself (not the guest). */
    fun hostEnv(): Map<String, String> = mapOf(
        "PROOT_LOADER" to File(nativeDir, "libproot-loader.so").path,
        "PROOT_TMP_DIR" to tmp.path,
        "LD_LIBRARY_PATH" to nativeDir.path,
    )

    /** Android hides some /proc files from apps; programs (apt, top, node) read them, so proot shows stand-ins. */
    private val fakeProc: File get() = File(base, "proc")
    private fun writeFakeProc() {
        val d = fakeProc.apply { mkdirs() }
        File(d, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(d, "uptime").writeText("124.08 932.80\n")
        File(d, "version").writeText("Linux version 6.2.1-AgentMT (agentmt@localhost) #1 SMP PREEMPT_DYNAMIC\n")
        File(d, "vmstat").writeText("nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\npgpgin 198391\npgpgout 0\n")
        File(d, "stat").writeText("cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 1957 0 2877 93280 262 342 254 87 0 0\n" +
            "intr 63361 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
        File(d, "cap_last_cap").writeText("40\n")
    }

    private fun readable(path: String) = runCatching { File(path).inputStream().use { it.read() }; true }.getOrDefault(false)

    /** Full argv to run [command] (or a login shell) inside Linux with [cwd] visible at the same path. */
    fun argv(cwd: File?, command: String?): List<String> {
        val d = active ?: Distro.Ubuntu
        val root = rootOf(d)
        val a = mutableListOf(File(nativeDir, "libproot.so").path, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "--kernel-release=6.2.1-AgentMT", "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin", "-b", "/proc/self/fd/1:/dev/stdout", "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${File(root, "tmp").path}:/dev/shm")
        if (!fakeProc.exists()) writeFakeProc()
        listOf("loadavg", "stat", "uptime", "version", "vmstat").forEach { f ->
            if (!readable("/proc/$f")) a += listOf("-b", "${File(fakeProc, f).path}:/proc/$f")
        }
        if (!readable("/proc/sys/kernel/cap_last_cap")) a += listOf("-b", "${File(fakeProc, "cap_last_cap").path}:/proc/sys/kernel/cap_last_cap")
        AndroidBridge.dir.mkdirs(); ensureHelpers(root)
        a += listOf("-b", "${AndroidBridge.dir.path}:/var/lib/agentmt-bridge")
        val (guestCwd, bind) = guestCwd(root, cwd)
        if (bind != null) a += listOf("-b", bind.path)
        if (File("/storage").exists()) a += listOf("-b", "/storage")
        if (File("/sdcard").exists()) a += listOf("-b", "/sdcard")
        a += listOf("-w", guestCwd)
        a += listOf("/usr/bin/env", "-i", "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "TMPDIR=/tmp")
        a += if (command == null) listOf(d.shell, "-l") else listOf(d.shell, "-lc", command)
        return a
    }

    /**
     * Where [cwd] is inside Linux, and which host folder (if any) must be bound for it.
     * A host "/" must never be bound: it hides the whole rootfs (v0.9.2 bug: kits failed with "/usr/bin/env not found").
     */
    fun guestCwd(root: File, cwd: File?): Pair<String, File?> {
        val p = cwd?.absoluteFile?.normalize()?.path ?: return "/root" to null
        val r = root.absoluteFile.normalize().path
        return when {
            p == r -> "/" to null
            p.startsWith("$r/") -> p.removePrefix(r) to null
            r.startsWith("$p/") -> "/root" to null // app files dir or another parent of the rootfs
            p == "/" || p.count { it == '/' } < 2 && !p.startsWith("/storage") && !p.startsWith("/sdcard") -> "/root" to null
            p.startsWith("/storage") || p.startsWith("/sdcard") -> p to null
            else -> p to File(p)
        }
    }

    fun process(cwd: File, command: String): ProcessBuilder =
        ProcessBuilder(argv(cwd, command)).directory(cwd).also { it.environment().putAll(hostEnv()) }

    /** Path of a guest file on the Android side. */
    fun hostPath(guest: String): File = File(rootfs, guest.removePrefix("/"))
}
