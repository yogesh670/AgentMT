package com.inyogeshwar.agentmt.core.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Base64

/**
 * Lets commands inside Linux reach Android (open a link or file, share, clipboard, notify, toast), in the spirit of
 * termux-tools/termux-api, written from scratch. A command drops a request file into a folder proot binds at
 * /var/lib/agentmt-bridge; the app answers in a .resp file. Only this app and its Linux can see that folder.
 *
 * Request file: line 1 the action, line 2 the argument as base64. Response: text the command prints.
 */
object AndroidBridge {
    const val VERSION = "2"
    private lateinit var app: Context
    lateinit var dir: File; private set
    private var observer: FileObserver? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(app.filesDir, "linux/bridge").apply { mkdirs() }
        observer = (if (Build.VERSION.SDK_INT >= 29) object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) { path?.let(::onFile) }
        } else @Suppress("DEPRECATION") object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) { path?.let(::onFile) }
        }).also { it.startWatching() }
    }

    private fun onFile(name: String) {
        if (!name.endsWith(".req")) return
        val f = File(dir, name)
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        f.delete()
        val action = lines.getOrNull(0)?.trim().orEmpty()
        val arg = runCatching { String(Base64.getDecoder().decode(lines.getOrNull(1)?.trim().orEmpty())) }.getOrDefault("")
        val cwd = lines.getOrNull(2)?.trim().orEmpty()
        main.post {
            val reply = runCatching { handle(action, arg, cwd) }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            val resp = File(dir, name.removeSuffix(".req") + ".resp")
            File(dir, resp.name + ".part").apply { writeText(reply) }.renameTo(resp)
        }
    }

    /** Runs one request on the main thread; returns what the command should print. */
    fun handle(action: String, arg: String, cwd: String): String = when (action) {
        "open" -> { open(arg, cwd, share = false); "" }
        "share" -> { open(arg, cwd, share = true); "" }
        "clipboard-get" -> (app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() ?: "")
        "clipboard-set" -> { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AgentMT", arg)); "" }
        "install" -> { install(arg, cwd); "Opening the Android installer for ${arg.substringAfterLast('/')}" }
        "toast" -> { Toast.makeText(app, arg.take(300), Toast.LENGTH_LONG).show(); "" }
        "notify" -> { notify(arg); "" }
        else -> error("unknown action '$action'")
    }

    private fun open(target: String, cwd: String, share: Boolean) {
        require(target.isNotBlank()) { "nothing to open" }
        val isUrl = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target) && !target.startsWith("file:")
        val intent = if (isUrl && !share) Intent(Intent.ACTION_VIEW, Uri.parse(target))
        else if (isUrl) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, target)
        else {
            val file = hostFile(target.removePrefix("file://"), cwd)
            require(file.isFile) { "no such file: $target" }
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            (if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(Intent.createChooser(intent, if (share) "Share" else "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun install(path: String, cwd: String) {
        val file = hostFile(path, cwd)
        require(file.isFile && file.name.endsWith(".apk")) { "not an .apk file: $path" }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            error("allow AgentMT to install apps (Settings just opened), then run agentmt-install again")
        }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Guest path → Android path: bound Android folders keep their path, everything else lives in the rootfs. */
    fun hostFile(path: String, cwd: String): File {
        val abs = if (path.startsWith("/")) path else "${cwd.trimEnd('/')}/$path"
        val direct = File(abs)
        val bound = abs.startsWith("/storage/") || abs.startsWith("/sdcard/") || abs.startsWith(app.filesDir.parentFile!!.path)
        return if (bound && direct.exists()) direct else LinuxEnv.hostPath(abs)
    }

    private fun notify(text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("linux", "Linux commands", NotificationManager.IMPORTANCE_DEFAULT))
        val title = text.substringBefore('\n').take(80).ifBlank { "AgentMT" }
        val body = text.substringAfter('\n', "")
        nm.notify((System.currentTimeMillis() % 100000).toInt(), NotificationCompat.Builder(app, "linux")
            .setSmallIcon(com.inyogeshwar.agentmt.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(body.ifBlank { null }).setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { title }))
            .setAutoCancel(true).build())
    }

    /** POSIX sh scripts (work in bash and busybox ash). */
    fun scripts(): Map<String, String> {
        val core = """
            #!/bin/sh
            # agentmt-bridge: talks to the AgentMT app. Usage: agentmt-bridge <action> [text]
            B=/var/lib/agentmt-bridge
            [ -d "${'$'}B" ] || { echo "agentmt: Android bridge not available" >&2; exit 1; }
            action="${'$'}1"; shift
            if [ "${'$'}#" -gt 0 ]; then arg="${'$'}*"; elif [ ! -t 0 ]; then arg="${'$'}(cat)"; else arg=""; fi
            id="${'$'}${'$'}-${'$'}(date +%s)-${'$'}RANDOM"
            { printf '%s\n' "${'$'}action"; printf '%s' "${'$'}arg" | base64 | tr -d '\n'; printf '\n%s\n' "${'$'}(pwd)"; } > "${'$'}B/${'$'}id.tmp"
            mv "${'$'}B/${'$'}id.tmp" "${'$'}B/${'$'}id.req"
            i=0
            while [ ! -f "${'$'}B/${'$'}id.resp" ]; do
              i=${'$'}((i+1)); [ ${'$'}i -gt 100 ] && { echo "agentmt: no answer from the app (is AgentMT open?)" >&2; exit 1; }
              sleep 0.1
            done
            out="${'$'}(cat "${'$'}B/${'$'}id.resp")"; rm -f "${'$'}B/${'$'}id.resp"
            case "${'$'}out" in error:*) echo "${'$'}out" >&2; exit 1;; esac
            [ -n "${'$'}out" ] && printf '%s\n' "${'$'}out"
            exit 0
        """.trimIndent() + "\n"
        fun wrap(action: String, usage: String) = "#!/bin/sh\n# $usage\nexec agentmt-bridge $action \"\$@\"\n"
        return mapOf(
            "agentmt-bridge" to core,
            "agentmt-open" to wrap("open", "agentmt-open <url|file>: open in an Android app"),
            "agentmt-share" to wrap("share", "agentmt-share <file|text>: Android share sheet"),
            "agentmt-clipboard-get" to wrap("clipboard-get", "agentmt-clipboard-get: print the Android clipboard"),
            "agentmt-clipboard-set" to wrap("clipboard-set", "agentmt-clipboard-set <text> (or pipe text in)"),
            "agentmt-notify" to wrap("notify", "agentmt-notify <title>  (pipe a body in, or use a newline)"),
            "agentmt-toast" to wrap("toast", "agentmt-toast <text>"),
            "agentmt-install" to wrap("install", "agentmt-install <file.apk>: open Android's installer"),
        )
    }
}
