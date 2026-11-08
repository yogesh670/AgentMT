package com.inyogeshwar.agentmt.feature.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import java.io.File

private val TermBg = Color(0xFF0E0F12)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(c: AppContainer, cwd: File, onBack: () -> Unit, onLinux: () -> Unit = {}) {
    val tm = c.terminals
    val tabs by tm.tabs.collectAsState()
    val selected by tm.selected.collectAsState()
    val linux by LinuxEnv.state.collectAsState()
    val scope = rememberCoroutineScope()
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var fontPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    if (fontPx == 0) fontPx = with(density) { 13.sp.roundToPx() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var showSetup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (tm.tabs.value.isEmpty()) tm.open(cwd, linux = LinuxEnv.ready) }
    val current = tabs.getOrNull(selected)

    Scaffold(topBar = {
        TopAppBar(title = { Text("Terminal") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (LinuxEnv.supported) TextButton(onClick = { if (linux is LinuxEnv.State.Ready) showSetup = true else onLinux() }) {
                    Text((linux as? LinuxEnv.State.Ready)?.let { "${it.distro.label} ✓" } ?: "Get Linux") }
                IconButton(onClick = { tm.open(cwd, linux = LinuxEnv.ready) }) { Icon(Icons.Filled.Add, "New tab") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            // Tabs
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t ->
                    val sel = i == selected
                    Row(Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { tm.selected.value = i }.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text((if (t.linux) "🐧 " else "▸ ") + t.title + " " + t.id + if (!t.session.isRunning) " (ended)" else "",
                            style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { tm.close(t) }, Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(14.dp)) }
                    }
                }
            }
            if (current == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = { tm.open(cwd, linux = LinuxEnv.ready) }) { Icon(Icons.Outlined.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Open a terminal") }
                }
            } else {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(TermBg),
                    factory = { ctx ->
                        newTerminalView(ctx, fontPx).apply {
                            setTerminalViewClient(viewClient(ctx, this, { ctrl }, { alt }, { ctrl = false; alt = false },
                                onScale = { s -> fontPx = (fontPx * s).toInt().coerceIn(18, 60); setTextSize(fontPx) }))
                            view = this
                        }
                    },
                    update = { v ->
                        if (v.currentSession !== current.session) { v.attachSession(current.session) }
                        tm.onUpdate = { v.onScreenUpdated() }
                        v.requestFocus()
                    })
                ExtraKeys(ctrl = ctrl, alt = alt, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt },
                    onKey = { code -> view?.handleKeyCode(code, 0) },
                    onText = { s -> current.session.write(s.toByteArray(), 0, s.toByteArray().size) },
                    onChar = { cp -> view?.inputCodePoint(cp, ctrl, alt); ctrl = false; alt = false })
            }
        }
    }

    if (showSetup) LinuxSheet(linux, onDismiss = { showSetup = false },
        onInstall = { showSetup = false; onLinux() },
        onOpen = { showSetup = false; tm.open(cwd, linux = true) },
        onRun = { cmd -> showSetup = false
            val t = tabs.firstOrNull { it.linux && it.session.isRunning } ?: tm.open(cwd, linux = true)
            tm.selected.value = tm.tabs.value.indexOf(t)
            val b = (cmd + "\n").toByteArray(); t.session.write(b, 0, b.size) },
        onRemove = { showSetup = false; onLinux() })
}

private fun viewClient(ctx: Context, v: TerminalView, ctrl: () -> Boolean, alt: () -> Boolean, reset: () -> Unit, onScale: (Float) -> Unit) =
    object : TerminalViewClient {
        override fun onScale(scale: Float): Float { if (scale < 0.9f || scale > 1.1f) { onScale(scale); return 1f }; return scale }
        override fun onSingleTapUp(e: MotionEvent?) {
            v.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrl()
        override fun readAltKey() = alt()
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean { reset(); return false }
        override fun onEmulatorSet() { v.setTerminalCursorBlinkerState(true, true) }
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

@Composable
private fun ExtraKeys(ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (Int) -> Unit, onText: (String) -> Unit, onChar: (Int) -> Unit) {
    @Composable fun K(label: String, active: Boolean = false, onClick: () -> Unit) {
        Box(Modifier.height(38.dp).widthIn(min = 42.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF1B1D22)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else Color(0xFFE6E6E6), fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    Row(Modifier.fillMaxWidth().background(TermBg).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        K("ESC") { onKey(KeyEvent.KEYCODE_ESCAPE) }
        K("TAB") { onKey(KeyEvent.KEYCODE_TAB) }
        K("CTRL", ctrl, onCtrl)
        K("ALT", alt, onAlt)
        K("←") { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        K("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        K("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP) }
        K("→") { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        K("HOME") { onKey(KeyEvent.KEYCODE_MOVE_HOME) }
        K("END") { onKey(KeyEvent.KEYCODE_MOVE_END) }
        K("PGUP") { onKey(KeyEvent.KEYCODE_PAGE_UP) }
        K("PGDN") { onKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        listOf("-", "/", "|", "~", "*", "$", "&", ">").forEach { s -> K(s) { onChar(s.codePointAt(0)) } }
        K("^C") { onText("\u0003") }
        K("^D") { onText("\u0004") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinuxSheet(state: LinuxEnv.State, onDismiss: () -> Unit, onInstall: () -> Unit, onOpen: () -> Unit, onRun: (String) -> Unit, onRemove: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val d = (state as? LinuxEnv.State.Ready)?.distro
            Text("Linux environment", style = MaterialTheme.typography.titleLarge)
            Text("${d?.label ?: "Linux"} ${d?.version ?: ""} running inside AgentMT (proot, no root needed). Install real packages with ${d?.pkg ?: "apt"}: Python, Node.js, git, gcc, ffmpeg and thousands more. The agent's run_command uses it too.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state) {
                LinuxEnv.State.Unsupported -> Text("Needs a 64-bit ARM (arm64) phone. The Android shell still works.", color = MaterialTheme.colorScheme.error)
                LinuxEnv.State.NotInstalled -> Button(onClick = onInstall, Modifier.fillMaxWidth()) { Text("Install Linux") }
                is LinuxEnv.State.Installing -> {
                    Text(state.step, style = MaterialTheme.typography.bodyMedium)
                    if (state.progress != null) LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is LinuxEnv.State.Failed -> {
                    Text("Install failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onInstall, Modifier.fillMaxWidth()) { Text("Try again") }
                }
                is LinuxEnv.State.Ready -> {
                    val dd = state.distro
                    Button(onClick = onOpen, Modifier.fillMaxWidth()) { Text("Open Linux terminal") }
                    Text("Quick installs", style = MaterialTheme.typography.titleSmall)
                    val u = dd == com.inyogeshwar.agentmt.core.linux.Distro.Ubuntu
                    val pkgs = if (u) listOf("Python" to "apt update && apt install -y python3 python3-pip python3-venv", "Node.js" to "apt update && apt install -y nodejs npm",
                        "Git" to "apt update && apt install -y git openssh-client", "Build tools" to "apt update && apt install -y build-essential",
                        "FFmpeg" to "apt update && apt install -y ffmpeg", "Java 17" to "apt update && apt install -y openjdk-17-jdk-headless", "Update all" to "apt update && apt upgrade -y")
                    else listOf("Python" to "apk add python3 py3-pip", "Node.js" to "apk add nodejs npm", "Git" to "apk add git openssh",
                        "Build tools" to "apk add build-base", "FFmpeg" to "apk add ffmpeg", "Java 17" to "apk add openjdk17-jdk",
                        "Update all" to "apk update && apk upgrade")
                    pkgs.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (l, cmd) -> AssistChip(onClick = { onRun(cmd) }, label = { Text(l) }) } }
                    }
                    TextButton(onClick = onRemove) { Text("Manage Linux, build tools & tools") }
                }
            }
        }
    }
}

/** setTextSize must come first: it creates the renderer that setTypeface reads (crashed on device in v0.4.0). */
internal fun newTerminalView(ctx: Context, fontPx: Int): TerminalView = TerminalView(ctx, null).apply {
    setBackgroundColor(android.graphics.Color.parseColor("#0E0F12"))
    setTextSize(fontPx)
    setTypeface(Typeface.MONOSPACE)
    isFocusable = true; isFocusableInTouchMode = true
}
