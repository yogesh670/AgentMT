package com.inyogeshwar.agentmt.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.inyogeshwar.agentmt.data.db.AppDatabase
import com.inyogeshwar.agentmt.data.prefs.SettingsStore
import com.inyogeshwar.agentmt.data.prefs.mergeJson
import com.inyogeshwar.agentmt.data.prefs.toJson
import com.inyogeshwar.agentmt.data.secure.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ExportOptions(
    val includeChats: Boolean = true,
    val includeProjectFiles: Boolean = true,
    val includeAttachments: Boolean = true,
    /** When set, API keys and tokens are included, encrypted with this passphrase. */
    val passphrase: String? = null,
)

data class ExportResult(val bytes: Long, val counts: Map<String, Int>, val secrets: Int, val skippedProjects: List<String>)

data class ImportPreview(
    val file: File,
    val appVersion: String,
    val createdAt: Long,
    val device: String,
    val counts: Map<String, Int>,
    val duplicates: Map<String, Int>,
    val missingProjects: List<String>,
    val projectFiles: Int,
    val hasSecrets: Boolean,
    val warnings: List<String>,
)

data class ImportResult(val inserted: Map<String, Int>, val skipped: Int, val secretsRestored: Int, val keysNeeded: Int, val missingProjects: List<String>)

/**
 * Portable workspace archive (.agentmt.zip):
 *   manifest.json, settings.json, db/<table>.json, files/{skills,commands,plugins,attachments}/…, projects/<id>/…, secrets.enc
 * Rows are written column-by-column so newer app versions can read older archives (unknown columns are ignored,
 * missing ones take their defaults). Secrets are never written in plaintext.
 */
class WorkspaceBackup(
    private val context: Context,
    private val db: AppDatabase,
    private val vault: SecretVault,
    private val settings: SettingsStore,
    private val privateProjectsDir: () -> File,
) {
    companion object {
        const val FORMAT = "agentmt-workspace"
        const val FORMAT_VERSION = 1
        const val DB_VERSION = 2
        val CHAT_TABLES = listOf("sessions", "messages", "runs")
        val TABLES = listOf("projects", "providers", "credentials", "routing_profiles", "rules", "mcp_servers", "tags", "tag_links", "plugins", "usage") + CHAT_TABLES
        private val FILE_DIRS = listOf("skills", "commands", "plugins")
        private const val MAX_PROJECT_BYTES = 200L * 1024 * 1024
        private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".venv", "__pycache__", "dist", ".next", "target")
        private val PK = mapOf("tags" to listOf("name"), "tag_links" to listOf("tag", "kind", "ref"), "usage" to listOf("day", "credentialId", "model"))
    }

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    private fun dump(table: String, where: String? = null): JSONArray {
        val arr = JSONArray()
        sql.query("SELECT * FROM `$table`" + (where?.let { " WHERE $it" } ?: "")).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    val n = c.getColumnName(i)
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> o.put(n, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> o.put(n, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> o.put(n, c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> o.put(n, JSONObject().put("\$b64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)))
                        else -> o.put(n, c.getString(i))
                    }
                }
                arr.put(o)
            }
        }
        return arr
    }

    suspend fun export(target: Uri, opt: ExportOptions): ExportResult = withContext(Dispatchers.IO) {
        val counts = LinkedHashMap<String, Int>(); var secrets = 0; val skippedProjects = ArrayList<String>()
        context.contentResolver.openOutputStream(target, "wt")!!.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                fun putFile(name: String, f: File) { zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }

                for (t in TABLES) {
                    if (!opt.includeChats && t in CHAT_TABLES) continue
                    val rows = if (!opt.includeChats && t == "tag_links") dump(t, "kind != 'session'") else dump(t)
                    counts[t] = rows.length()
                    put("db/$t.json", rows.toString().toByteArray())
                }
                put("settings.json", settings.state.value.toJson().toString(2).toByteArray())

                for (d in FILE_DIRS) {
                    val base = File(context.filesDir, d)
                    base.walkTopDown().filter { it.isFile }.forEach { putFile("files/$d/" + it.relativeTo(base).path, it) }
                }
                if (opt.includeChats && opt.includeAttachments) {
                    val base = File(context.filesDir, "attachments")
                    base.walkTopDown().filter { it.isFile }.forEach { putFile("files/attachments/" + it.relativeTo(base).path, it) }
                }
                var projectFiles = 0
                if (opt.includeProjectFiles) {
                    val priv = privateProjectsDir().canonicalPath
                    for (p in db.projects().all()) {
                        val root = File(p.path)
                        if (!root.isDirectory) continue
                        // Only app-owned projects are copied; folders elsewhere on the phone are referenced by path.
                        if (!root.canonicalPath.startsWith(priv)) { skippedProjects += p.name; continue }
                        var size = 0L
                        root.walkTopDown().onEnter { it == root || it.name !in SKIP_DIRS }.filter { it.isFile }.forEach { f ->
                            size += f.length()
                            if (size <= MAX_PROJECT_BYTES) { putFile("projects/${p.id}/" + f.relativeTo(root).path, f); projectFiles++ }
                        }
                        if (size > MAX_PROJECT_BYTES) skippedProjects += "${p.name} (over 200 MB, partially copied)"
                    }
                }
                if (opt.passphrase != null) {
                    val o = JSONObject()
                    vault.names().filter { it.startsWith("cred:") || it.startsWith("mcp:") || it == "github" }
                        .forEach { n -> vault.get(n)?.let { o.put(n, it); secrets++ } }
                    put("secrets.enc", BackupCrypto.encrypt(o.toString().toByteArray(), opt.passphrase))
                }
                val manifest = JSONObject()
                    .put("format", FORMAT).put("formatVersion", FORMAT_VERSION).put("dbVersion", DB_VERSION)
                    .put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
                    .put("createdAt", System.currentTimeMillis()).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("counts", JSONObject(counts as Map<*, *>)).put("projectFiles", projectFiles)
                    .put("includes", JSONObject().put("chats", opt.includeChats).put("attachments", opt.includeChats && opt.includeAttachments)
                        .put("projectFiles", opt.includeProjectFiles).put("secrets", opt.passphrase != null))
                put("manifest.json", manifest.toString(2).toByteArray())
            }
        }
        val size = context.contentResolver.openFileDescriptor(target, "r")?.use { it.statSize } ?: -1
        ExportResult(size, counts, secrets, skippedProjects)
    }

    /** Copies the archive locally and checks it without changing anything. */
    suspend fun preview(source: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "import.zip")
        context.contentResolver.openInputStream(source)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        val zip = runCatching { ZipFile(f) }.getOrElse { throw IllegalArgumentException("This file is not a ZIP archive.") }
        zip.use { z ->
            val m = z.getEntry("manifest.json")?.let { JSONObject(z.getInputStream(it).bufferedReader().readText()) }
                ?: throw IllegalArgumentException("Not an AgentMT workspace backup (manifest.json missing).")
            if (m.optString("format") != FORMAT) throw IllegalArgumentException("Not an AgentMT workspace backup.")
            val fv = m.optInt("formatVersion"); val dv = m.optInt("dbVersion")
            if (fv > FORMAT_VERSION || dv > DB_VERSION)
                throw IllegalArgumentException("This backup was made by a newer AgentMT (${m.optString("appVersion")}). Update the app first.")
            val counts = LinkedHashMap<String, Int>(); val dups = LinkedHashMap<String, Int>(); val warnings = ArrayList<String>()
            val missing = ArrayList<String>()
            val projectIdsWithFiles = z.entries().asSequence().map { it.name }.filter { it.startsWith("projects/") }.map { it.split('/')[1] }.toSet()
            for (t in TABLES) {
                val e = z.getEntry("db/$t.json") ?: continue
                val rows = runCatching { JSONArray(z.getInputStream(e).bufferedReader().readText()) }
                    .getOrElse { throw IllegalArgumentException("db/$t.json is damaged.") }
                counts[t] = rows.length()
                val keys = PK[t] ?: listOf("id")
                if (t == "messages" || t == "rules") continue
                var d = 0
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    val where = keys.joinToString(" AND ") { "`$it` = ?" }
                    val args = keys.map { o.opt(it)?.toString() ?: "" }.toTypedArray()
                    sql.query("SELECT 1 FROM `$t` WHERE $where LIMIT 1", args).use { if (it.moveToFirst()) d++ }
                    if (t == "projects") {
                        val path = o.optString("path"); val id = o.optString("id")
                        if (id !in projectIdsWithFiles && !File(path).isDirectory) missing += "${o.optString("name")} ($path)"
                    }
                }
                if (d > 0) dups[t] = d
            }
            if (m.optJSONObject("includes")?.optBoolean("secrets") != true) warnings += "API keys are not included. You'll add them again under Providers."
            ImportPreview(f, m.optString("appVersion"), m.optLong("createdAt"), m.optString("device"), counts, dups, missing,
                m.optInt("projectFiles"), z.getEntry("secrets.enc") != null, warnings)
        }
    }

    /**
     * Restores [p]. When [replace] is false, rows that already exist are kept as they are.
     * Secrets are decrypted first, so a wrong passphrase stops the import before anything changes.
     */
    suspend fun import(p: ImportPreview, replace: Boolean, passphrase: String?): ImportResult = withContext(Dispatchers.IO) {
        ZipFile(p.file).use { z ->
            val secrets: JSONObject? = z.getEntry("secrets.enc")?.takeIf { !passphrase.isNullOrEmpty() }?.let {
                JSONObject(String(BackupCrypto.decrypt(z.getInputStream(it).readBytes(), passphrase!!)))
            }
            val inserted = LinkedHashMap<String, Int>(); var skipped = 0
            val projectFileIds = z.entries().asSequence().map { it.name }.filter { it.startsWith("projects/") }.map { it.split('/')[1] }.toSet()
            val pathRemap = HashMap<String, String>()
            val skippedSessions = HashSet<String>()
            val db0 = sql
            db0.beginTransaction()
            try {
                for (t in TABLES) {
                    val e = z.getEntry("db/$t.json") ?: continue
                    val rows = JSONArray(z.getInputStream(e).bufferedReader().readText())
                    val cols = columns(t)
                    var n = 0
                    for (i in 0 until rows.length()) {
                        val o = rows.getJSONObject(i)
                        if (t == "messages" && o.optString("sessionId") in skippedSessions) { skipped++; continue }
                        if (t == "projects" && o.optString("id") in projectFileIds) {
                            val dir = uniqueDir(o.optString("name").ifBlank { "project" })
                            pathRemap[o.optString("id")] = dir.path
                            o.put("path", dir.path)
                        }
                        val cv = ContentValues()
                        for (k in o.keys()) {
                            if (k !in cols) continue
                            if ((t == "messages" || t == "rules") && k == "id") continue // autoincrement ids are local
                            when (val v = o.get(k)) {
                                JSONObject.NULL -> cv.putNull(k)
                                is Int -> cv.put(k, v.toLong()); is Long -> cv.put(k, v); is Double -> cv.put(k, v)
                                is Boolean -> cv.put(k, if (v) 1L else 0L)
                                is JSONObject -> cv.put(k, Base64.decode(v.optString("\$b64"), Base64.NO_WRAP))
                                else -> cv.put(k, v.toString())
                            }
                        }
                        if (t == "rules") {
                            val exists = db0.query("SELECT 1 FROM rules WHERE tool = ? AND pattern = ? AND action = ?",
                                arrayOf(o.optString("tool"), o.optString("pattern"), o.optString("action"))).use { it.moveToFirst() }
                            if (exists) { skipped++; continue }
                        }
                        if (t == "sessions") {
                            val exists = db0.query("SELECT 1 FROM sessions WHERE id = ?", arrayOf(o.optString("id"))).use { it.moveToFirst() }
                            if (exists && !replace) { skippedSessions += o.optString("id"); skipped++; continue }
                            if (exists) db0.execSQL("DELETE FROM messages WHERE sessionId = ?", arrayOf(o.optString("id")))
                        }
                        val conflict = if (replace) android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE else android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                        val r = db0.insert(t, conflict, cv)
                        if (r == -1L) skipped++ else n++
                    }
                    inserted[t] = n
                }
                db0.setTransactionSuccessful()
            } finally { db0.endTransaction(); db.invalidationTracker.refreshVersionsAsync() }

            // files
            for (e in z.entries().asSequence()) {
                if (e.isDirectory || e.name.contains("..")) continue
                val dest = when {
                    e.name.startsWith("files/") -> File(context.filesDir, e.name.removePrefix("files/"))
                    e.name.startsWith("projects/") -> {
                        val id = e.name.split('/')[1]
                        val base = pathRemap[id]?.let { File(it) } ?: continue
                        File(base, e.name.removePrefix("projects/$id/"))
                    }
                    else -> continue
                }
                if (dest.exists() && !replace && !e.name.startsWith("projects/")) continue
                dest.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> dest.outputStream().use { i.copyTo(it) } }
            }

            z.getEntry("settings.json")?.let { e ->
                val o = JSONObject(z.getInputStream(e).bufferedReader().readText())
                settings.update { it.mergeJson(o) }
            }
            var restored = 0
            secrets?.keys()?.forEach { k -> if (replace || !vault.has(k)) { vault.put(k, secrets.getString(k)); restored++ } }
            val keysNeeded = db.credentials().all().count { !vault.has("cred:${it.id}") }
            val missing = db.projects().all().filter { !File(it.path).isDirectory }.map { "${it.name} (${it.path})" }
            p.file.delete()
            ImportResult(inserted, skipped, restored, keysNeeded, missing)
        }
    }

    private fun columns(table: String): Set<String> =
        sql.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }

    private fun uniqueDir(name: String): File {
        val slug = name.replace(Regex("[^A-Za-z0-9._ -]"), "").replace(' ', '-').ifEmpty { "project" }
        var d = File(privateProjectsDir(), slug); var i = 2
        while (d.exists() && (d.list()?.isNotEmpty() == true)) d = File(privateProjectsDir(), "$slug-${i++}")
        d.mkdirs(); return d
    }
}
