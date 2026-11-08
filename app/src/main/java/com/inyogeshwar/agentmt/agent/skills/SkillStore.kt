package com.inyogeshwar.agentmt.agent.skills

import android.content.Context
import java.io.File

/** [source] = Built-in | Yours | Project | Plugin: <name> */
data class Skill(val name: String, val description: String, val source: String, val location: String)

/**
 * Skills are folders with a SKILL.md (YAML front matter: name, description), the format used by Claude Code,
 * anthropics/skills and skills.sh. Sources: bundled assets, the app's global skills folder, enabled plugins,
 * and <project>/.agentmt/skills (also .claude/skills for compatibility).
 */
class SkillStore(private val context: Context) {
    val userDir: File get() = File(context.filesDir, "skills").apply { mkdirs() }
    private var projectRoot: File? = null
    @Volatile var pluginDirs: List<Pair<String, File>> = emptyList()

    /** Skill folders inside installed Linux tools (GitHub repos). */
    var toolSkillDirs: () -> List<Pair<String, File>> = { emptyList() }

    fun setProject(root: File?) { projectRoot = root }
    fun projectDir(root: File) = File(root, ".agentmt/skills")

    fun all(): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        runCatching {
            context.assets.list("skills")?.forEach { dir ->
                val text = context.assets.open("skills/$dir/SKILL.md").bufferedReader().use { it.readText() }
                parse(text, dir, "Built-in", "asset:skills/$dir/SKILL.md")?.let { out[it.name] = it }
            }
        }
        val dirs = ArrayList<Pair<File, String>>()
        pluginDirs.forEach { (name, d) -> dirs += File(d, "skills") to "Plugin: $name" }
        dirs += userDir to "Yours"
        projectRoot?.let { dirs += File(it, ".claude/skills") to "Project"; dirs += projectDir(it) to "Project" }
        dirs.forEach { (base, label) ->
            base.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val f = File(d, "SKILL.md")
                if (f.exists()) parse(f.readText(), d.name, label, f.path)?.let { out[it.name] = it }
            }
        }
        runCatching { toolSkillDirs() }.getOrDefault(emptyList()).forEach { (tool, d) ->
            val f = File(d, "SKILL.md")
            if (f.exists()) parse(f.readText(), d.name, "Tool: $tool", f.path)?.let { out.putIfAbsent(it.name, it) }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    fun find(name: String) = all().firstOrNull { it.name.equals(name, true) }

    fun content(s: Skill): String = if (s.location.startsWith("asset:"))
        context.assets.open(s.location.removePrefix("asset:")).bufferedReader().use { it.readText() }
    else File(s.location).readText()

    fun supportingFiles(s: Skill): List<String> {
        if (s.location.startsWith("asset:")) return emptyList()
        val dir = File(s.location).parentFile ?: return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.take(60).map { it.relativeTo(dir).path }.toList()
    }

    fun supportingFile(s: Skill, rel: String): String? {
        if (s.location.startsWith("asset:")) return null
        val dir = File(s.location).parentFile?.canonicalFile ?: return null
        val f = File(dir, rel).canonicalFile
        if (!f.path.startsWith(dir.path + File.separator) || !f.isFile || f.length() > 2_000_000) return null
        return f.readText()
    }

    /** Writes a skill folder. [files] are extra supporting files (relative path → content). */
    fun save(name: String, body: String, project: File? = null, files: Map<String, ByteArray> = emptyMap()): File {
        val base = project?.let { projectDir(it) } ?: userDir
        val dir = File(base, slug(name)); dir.mkdirs()
        File(dir, "SKILL.md").writeText(body)
        files.forEach { (rel, bytes) ->
            val f = File(dir, rel).canonicalFile
            if (f.path.startsWith(dir.canonicalPath + File.separator)) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        }
        return dir
    }

    fun delete(s: Skill): Boolean =
        if (s.source == "Yours" || s.source == "Project") File(s.location).parentFile?.deleteRecursively() == true else false

    companion object {
        fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "skill" }

        fun parse(text: String, fallback: String, source: String, location: String): Skill? {
            var name = fallback; var desc = ""
            if (text.startsWith("---")) {
                val end = text.indexOf("\n---", 3)
                if (end > 0) text.substring(3, end).lines().forEach { l ->
                    val i = l.indexOf(':'); if (i > 0 && !l.startsWith(" ")) {
                        val k = l.substring(0, i).trim(); val v = l.substring(i + 1).trim().trim('"', '\'')
                        if (k == "name" && v.isNotBlank()) name = v
                        if (k == "description") desc = v
                    }
                }
            }
            return Skill(name, desc.ifBlank { text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("---") }.orEmpty().removePrefix("#").trim().take(160) }, source, location)
        }

        /** Adds front matter to a plain Markdown file so it becomes a valid skill. */
        fun ensureFrontMatter(text: String, name: String, description: String): String =
            if (text.trimStart().startsWith("---")) text
            else "---\nname: ${slug(name)}\ndescription: ${description.replace("\n", " ")}\n---\n\n$text"
    }
}
