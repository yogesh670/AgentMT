package com.inyogeshwar.agentmt.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last uncaught exception to app storage so the next launch can show it (no network, no analytics).
 * Secrets never appear in stack traces because they are never put in exception messages.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                File(app.filesDir, FILE).writeText(buildString {
                    append("AgentMT ").append(version).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                    append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" · thread ").append(t.name).append("\n\n")
                    append(sw.toString().take(20_000))
                })
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}
