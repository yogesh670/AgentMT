package com.inyogeshwar.agentmt.core.linux

import android.content.Context
import com.inyogeshwar.agentmt.agent.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Long Linux jobs (installing build tools, cloning a tool, apt install) that keep running when the user leaves the
 * screen. Each job keeps the tail of its output, with carriage returns handled so progress bars redraw in place.
 */
class LinuxJobs(private val context: Context) {
    data class Job(val id: Int, val title: String, val log: String = "", val running: Boolean = true, val exitCode: Int? = null) {
        val ok get() = exitCode == 0
        /** Last non-empty line, for one-line status. */
        val lastLine get() = log.trimEnd().substringAfterLast('\n').take(160)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicInteger(1)
    /** One job at a time: apt and apk hold a lock, and phones don't have cores to spare. */
    private val lock = Mutex()
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    fun running(titlePrefix: String) = _jobs.value.any { it.running && it.title.startsWith(titlePrefix) }

    /** Runs [script] with the distro's shell inside Linux. [onDone] gets the exit code on the IO thread. */
    fun run(title: String, script: String, cwd: File? = null, onDone: suspend (Int) -> Unit = {}): Int {
        val id = ids.getAndIncrement()
        _jobs.update { it + Job(id, title, log = if (lock.isLocked) "Waiting for the previous job…" else "") }
        AgentService.start(context, AgentService.HOLD_JOBS)
        scope.launch {
            val code = lock.withLock { runCatching {
                val f = LinuxEnv.hostPath("/tmp/agentmt-job-$id.sh")
                f.parentFile?.mkdirs(); f.writeText(script)
                val dir = cwd?.takeIf { it.isDirectory } ?: LinuxEnv.hostPath("/root")
                val proc = ProcessBuilder(LinuxEnv.argv(dir, "sh /tmp/agentmt-job-$id.sh")).directory(context.filesDir)
                    .also { it.environment().putAll(LinuxEnv.hostEnv()) }.redirectErrorStream(true).start()
                val tail = Tail()
                val buf = ByteArray(4096)
                proc.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        tail.feed(String(buf, 0, n))
                        val text = tail.text()
                        _jobs.update { l -> l.map { if (it.id == id) it.copy(log = text) else it } }
                    }
                }
                val c = proc.waitFor()
                f.delete(); c
            }.getOrElse { e -> _jobs.update { l -> l.map { if (it.id == id) it.copy(log = it.log + "\n" + (e.message ?: e.toString())) else it } }; -1 } }
            _jobs.update { l -> l.map { if (it.id == id) it.copy(running = false, exitCode = code) else it } }
            runCatching { onDone(code) }
            if (_jobs.value.none { it.running }) AgentService.stop(context, AgentService.HOLD_JOBS)
        }
        return id
    }

    /** Same as [run] but suspends until the job ends and returns its exit code. */
    suspend fun await(title: String, script: String, cwd: File = File("/")): Int {
        val done = CompletableDeferred<Int>()
        run(title, script, cwd) { done.complete(it) }
        return done.await()
    }

    fun dismiss(id: Int) = _jobs.update { l -> l.filterNot { it.id == id && !it.running } }

    /** Terminal-ish text buffer: '\r' rewinds to the line start, output is capped to the last ~16 KB. */
    class Tail(private val cap: Int = 16_000) {
        private val sb = StringBuilder()
        private var lineStart = 0
        private var cr = false
        fun feed(s: String) {
            for (ch in s) {
                when (ch) {
                    '\r' -> cr = true
                    '\n' -> { sb.append('\n'); lineStart = sb.length; cr = false }
                    else -> { if (cr) { sb.setLength(lineStart); cr = false }; sb.append(ch) }
                }
            }
            if (sb.length > cap) { val cut = sb.length - cap; sb.delete(0, cut); lineStart = (lineStart - cut).coerceAtLeast(0) }
        }
        fun text() = sb.toString()
    }
}
