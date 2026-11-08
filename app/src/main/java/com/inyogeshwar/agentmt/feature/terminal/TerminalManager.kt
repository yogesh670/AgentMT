package com.inyogeshwar.agentmt.feature.terminal

import android.content.Context
import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Keeps terminal sessions alive while the app runs, so leaving the screen doesn't kill the shell. */
class TerminalManager(private val context: Context) {
    data class Tab(val id: Int, val session: TerminalSession, val linux: Boolean, var title: String)

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs
    val selected = MutableStateFlow(0)
    private var next = 1

    /** Client that forwards session events to whichever view is showing it. */
    /** Set by the visible TerminalView so output repaints it. */
    var onUpdate: (() -> Unit)? = null

    val client: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { onUpdate?.invoke() }
        override fun onTitleChanged(s: TerminalSession) { _tabs.value = _tabs.value.toList() }
        override fun onSessionFinished(s: TerminalSession) { onUpdate?.invoke(); _tabs.value = _tabs.value.toList() }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text ?: ""))
        }
        override fun onPasteTextFromClipboard(s: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            s?.emulator?.paste(t)
        }
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) { onUpdate?.invoke() }
        override fun onTerminalCursorStateChange(state: Boolean) { onUpdate?.invoke() }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    fun open(cwd: File, linux: Boolean): Tab {
        val dir = cwd.takeIf { it.isDirectory } ?: context.filesDir
        val session = if (linux && LinuxEnv.ready) {
            val argv = LinuxEnv.argv(dir, null)
            val env = (LinuxEnv.hostEnv() + mapOf("TERM" to "xterm-256color", "HOME" to context.filesDir.path)).map { "${it.key}=${it.value}" }
            TerminalSession(argv[0], dir.path, arrayOf("proot") + argv.drop(1).toTypedArray(), env.toTypedArray(), 3000, client)
        } else {
            val env = arrayOf("TERM=xterm-256color", "HOME=${context.filesDir.path}", "TMPDIR=${context.cacheDir.path}",
                "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin",
                "ANDROID_ROOT=/system", "ANDROID_DATA=/data")
            TerminalSession("/system/bin/sh", dir.path, arrayOf("-sh"), env, 3000, client)
        }
        val tab = Tab(next++, session, linux && LinuxEnv.ready, if (linux && LinuxEnv.ready) (LinuxEnv.distro?.label?.substringBefore(' ') ?: "Linux") else "Android")
        _tabs.value = _tabs.value + tab
        selected.value = _tabs.value.lastIndex
        return tab
    }

    fun close(tab: Tab) {
        tab.session.finishIfRunning()
        _tabs.value = _tabs.value - tab
        selected.value = selected.value.coerceAtMost((_tabs.value.size - 1).coerceAtLeast(0))
    }
}
