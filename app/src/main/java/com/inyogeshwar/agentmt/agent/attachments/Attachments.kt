package com.inyogeshwar.agentmt.agent.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.inyogeshwar.agentmt.agent.llm.Part
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** kind = image | text | pdf | binary. [path] is relative to filesDir so backups stay portable. */
data class Attachment(val name: String, val mime: String, val path: String, val size: Long, val kind: String)

object Attachments {
    const val MAX_FILE = 25L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 120_000
    private const val MAX_IMAGE_SIDE = 1568

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "swift", "dart", "php", "html", "htm", "css", "scss", "json", "xml", "yaml", "yml", "toml", "ini", "gradle", "properties",
        "sh", "bat", "ps1", "sql", "csv", "tsv", "log", "env", "gitignore", "dockerfile", "vue", "svelte", "lua", "r", "tex", "srt", "vtt",
    )

    fun kindOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") && ext != "svg" -> "image"
            mime == "application/pdf" || ext == "pdf" -> "pdf"
            mime.startsWith("text/") || ext in TEXT_EXT || mime in setOf("application/json", "application/xml", "application/javascript") -> "text"
            else -> "binary"
        }
    }

    fun dir(c: Context) = File(c.filesDir, "attachments").apply { mkdirs() }

    /** Copies a picked document into app storage. */
    fun import(c: Context, uri: Uri): Attachment {
        var name = "file"; var size = -1L
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
            }
        }
        if (size > MAX_FILE) throw IllegalArgumentException("$name is larger than 25 MB")
        val mime = c.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val rel = "attachments/${UUID.randomUUID().toString().take(8)}-$safe"
        val out = File(c.filesDir, rel)
        c.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return Attachment(name, mime, rel, out.length(), kindOf(name, mime))
    }

    fun encode(list: List<Attachment>): String? = if (list.isEmpty()) null else JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("mime", it.mime).put("path", it.path).put("size", it.size).put("kind", it.kind)) }
    }.toString()

    fun decode(json: String?): List<Attachment> = if (json.isNullOrBlank()) emptyList() else runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Attachment(it.optString("name"), it.optString("mime"), it.optString("path"), it.optLong("size"), it.optString("kind"))
        }
    }.getOrDefault(emptyList())

    fun file(c: Context, a: Attachment) = File(c.filesDir, a.path)

    /**
     * Builds what the model sees: text-like files are inlined in the prompt; images and PDFs become content parts
     * (only when [binary] is true, so older turns don't resend large payloads).
     */
    fun materialize(c: Context, list: List<Attachment>, binary: Boolean): Pair<String, List<Part>> {
        val text = StringBuilder(); val parts = ArrayList<Part>()
        for (a in list) {
            val f = file(c, a)
            if (!f.exists()) { text.append("\n\n[Attachment ${a.name} is missing on this device]"); continue }
            when (a.kind) {
                "text" -> {
                    val body = runCatching { f.readText() }.getOrDefault("")
                    val lang = a.name.substringAfterLast('.', "")
                    text.append("\n\n<attached_file name=\"${a.name}\">\n```$lang\n")
                        .append(body.take(MAX_TEXT_CHARS)).append(if (body.length > MAX_TEXT_CHARS) "\n… (truncated)" else "")
                        .append("\n```\n</attached_file>")
                }
                "image" -> if (binary) imagePart(f)?.let { parts += it } ?: text.append("\n\n[Image ${a.name} could not be read]")
                    else text.append("\n\n[Image ${a.name} was shared earlier]")
                "pdf" -> if (binary) parts += Part.Pdf(a.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    else text.append("\n\n[PDF ${a.name} was shared earlier]")
                else -> text.append("\n\n[Attached file ${a.name} (${a.mime}, ${a.size / 1024} KB) — binary content not readable by the model. Saved at ${f.path}]")
            }
        }
        return text.toString() to parts
    }

    private fun imagePart(f: File): Part.Image? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Part.Image("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
