package com.inyogeshwar.agentmt.core.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands in the user's own Termux app through its public RUN_COMMAND intent
 * (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). Clean-room: only the documented
 * action, extras and result bundle keys are used. Needs Termux with allow-external-apps=true and
 * the com.termux.permission.RUN_COMMAND permission granted to AgentMT.
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private val ids = AtomicInteger(1)
    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** Set by AppContainer: the user switched "Use my Termux" on. */
    var enabled: () -> Boolean = { false }
    val active: Boolean get() = enabled() && ready()
    private fun ctx(): Context = app ?: error("TermuxBridge not initialised")

    data class Result(val stdout: String, val stderr: String, val exitCode: Int, val err: Int, val errmsg: String) {
        val ok get() = err == -1 && exitCode == 0
        fun text(): String = buildString {
            if (stdout.isNotEmpty()) append(stdout.trimEnd()).append('\n')
            if (stderr.isNotEmpty()) append(stderr.trimEnd()).append('\n')
            if (errmsg.isNotBlank() && err != -1) append("[termux error $err] ").append(errmsg.trim()).append('\n')
            append("[exit $exitCode]")
        }
    }

    fun installed(c: Context = ctx()): Boolean = runCatching { c.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)
    fun permitted(c: Context = ctx()): Boolean = ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun ready(c: Context? = app): Boolean = c != null && installed(c) && permitted(c)

    /** Termux can only see shared storage (after termux-setup-storage) or its own home, never AgentMT's private folder. */
    fun canSee(path: String): Boolean = path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith(HOME)

    /** One-time setup the user pastes into Termux. */
    const val SETUP = "mkdir -p ~/.termux; sed -i '/allow-external-apps/d' ~/.termux/termux.properties 2>/dev/null; echo 'allow-external-apps=true' >> ~/.termux/termux.properties; termux-reload-settings; termux-setup-storage; pkg install -y nodejs python git"

    /**
     * Runs [command] with bash -lc in Termux's background and waits for its result.
     * Returns null on timeout (the command keeps running inside Termux).
     */
    suspend fun run(command: String, workdir: String? = null, timeoutMs: Long = 60_000): Result? {
        val c = ctx()
        if (!installed(c)) throw IllegalStateException("Termux is not installed")
        if (!permitted(c)) throw IllegalStateException("AgentMT is not allowed to run commands in Termux yet (Linux → Termux → Grant)")
        val n = ids.getAndIncrement()
        val action = c.packageName + ".TERMUX_RESULT." + n
        val done = CompletableDeferred<Result>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val b = intent.getBundleExtra("result")
                done.complete(Result(b?.getString("stdout").orEmpty(), b?.getString("stderr").orEmpty(),
                    b?.getInt("exitCode", -1) ?: -1, b?.getInt("err", -1) ?: -1, b?.getString("errmsg").orEmpty()))
            }
        }
        ContextCompat.registerReceiver(c, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        try {
            val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, n, Intent(action).setPackage(c.packageName), flags)
            val i = Intent().setClassName(PKG, SERVICE).setAction(ACTION)
                .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            workdir?.let { i.putExtra("com.termux.RUN_COMMAND_WORKDIR", it) }
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            return withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { c.unregisterReceiver(receiver) }
        }
    }

    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
