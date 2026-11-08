package com.inyogeshwar.agentmt.feature.browser

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Browser extensions as userscripts (the Tampermonkey / Greasemonkey format, e.g. from greasyfork.org).
 * Chrome Web Store extensions can't run in Android's WebView, but most page-tweaking extensions exist as userscripts.
 */
data class UserScript(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val runAt: String,
    val source: String,
    val enabled: Boolean = true,
) {
    fun appliesTo(url: String): Boolean {
        if (excludes.any { UserScriptMeta.globMatch(it, url) || UserScriptMeta.patternMatch(it, url) }) return false
        if (matches.isEmpty() && includes.isEmpty()) return false
        return matches.any { UserScriptMeta.patternMatch(it, url) } || includes.any { UserScriptMeta.globMatch(it, url) }
    }
}

object UserScriptMeta {
    /** Reads the ==UserScript== block. Returns null when the text has none. */
    fun parse(code: String, id: String = UUID.randomUUID().toString(), source: String = "", enabled: Boolean = true): UserScript? {
        val block = Regex("""//\s*==UserScript==(.*?)//\s*==/UserScript==""", RegexOption.DOT_MATCHES_ALL).find(code)?.groupValues?.get(1) ?: return null
        val tags = ArrayList<Pair<String, String>>()
        block.lines().forEach { line ->
            val m = Regex("""^\s*//\s*@([\w:.-]+)\s*(.*)$""").find(line) ?: return@forEach
            tags += m.groupValues[1] to m.groupValues[2].trim()
        }
        fun one(k: String) = tags.firstOrNull { it.first == k }?.second.orEmpty()
        fun all(k: String) = tags.filter { it.first == k }.map { it.second }.filter { it.isNotEmpty() }
        return UserScript(id, one("name").ifEmpty { "Untitled script" }, one("description"), one("version"),
            all("match"), all("include"), all("exclude") + all("exclude-match"), one("run-at").ifEmpty { "document-end" }, source, enabled)
    }

    /** Chrome match patterns: scheme://host/path with * wildcards; <all_urls>. */
    fun patternMatch(p: String, url: String): Boolean {
        if (p == "<all_urls>") return url.startsWith("http")
        val m = Regex("""^(\*|https?|file|ftp)://([^/]*)(/.*)?$""").find(p) ?: return false
        val (scheme, host, path) = m.destructured
        val u = Regex("""^(\w+)://([^/:]*)(?::\d+)?(/[^#]*)?""").find(url) ?: return false
        val (us, uh, up) = u.destructured
        if (scheme == "*") { if (us != "http" && us != "https") return false } else if (scheme != us) return false
        val hostOk = when {
            host == "*" -> true
            host.startsWith("*.") -> uh == host.drop(2) || uh.endsWith("." + host.drop(2))
            else -> uh.equals(host, true)
        }
        if (!hostOk) return false
        return globMatch(path.ifEmpty { "/" }, up.ifEmpty { "/" })
    }

    /** @include globs (* = anything) or /regex/. */
    fun globMatch(g: String, s: String): Boolean {
        if (g.length > 2 && g.startsWith("/") && g.endsWith("/")) return runCatching { Regex(g.drop(1).dropLast(1)).containsMatchIn(s) }.getOrDefault(false)
        if (g == "*") return true
        val rx = "^" + g.split("*").joinToString(".*") { Regex.escape(it) } + "$"
        return Regex(rx).matches(s)
    }

    /** Wraps a userscript with the GM_* functions most scripts use. */
    fun wrap(s: UserScript): String {
        val key = s.id.take(8)
        val info = JSONObject().put("script", JSONObject().put("name", s.name).put("version", s.version).put("description", s.description)).toString()
        return """
(function(){
if (window['__agentmt_us_$key']) return; window['__agentmt_us_$key'] = true;
var GM_info = $info;
var unsafeWindow = window;
function GM_addStyle(css){ var e=document.createElement('style'); e.textContent=css; (document.head||document.documentElement).appendChild(e); return e; }
function GM_getValue(k,d){ try{ var v=localStorage.getItem('__us_${key}_'+k); return v===null?d:JSON.parse(v);}catch(e){return d;} }
function GM_setValue(k,v){ try{ localStorage.setItem('__us_${key}_'+k, JSON.stringify(v)); }catch(e){} }
function GM_deleteValue(k){ try{ localStorage.removeItem('__us_${key}_'+k); }catch(e){} }
function GM_listValues(){ var o=[]; for (var i=0;i<localStorage.length;i++){ var k=localStorage.key(i); if(k.indexOf('__us_${key}_')==0) o.push(k.slice(${key.length + 6})); } return o; }
function GM_log(m){ console.log(m); }
function GM_openInTab(u){ location.href=u; }
function GM_setClipboard(t){ try{ navigator.clipboard.writeText(t); }catch(e){} }
function GM_registerMenuCommand(){ return 0; }
function GM_xmlhttpRequest(d){ fetch(d.url,{method:d.method||'GET',headers:d.headers||{},body:d.data,credentials:'include'})
  .then(function(r){ return r.text().then(function(t){ var resp={responseText:t,response:t,status:r.status,statusText:r.statusText,finalUrl:r.url}; if(d.onload) d.onload(resp); if(d.onloadend) d.onloadend(resp); }); })
  .catch(function(e){ if(d.onerror) d.onerror(e); }); }
var GM = { info: GM_info, addStyle: GM_addStyle, getValue: function(k,d){ return Promise.resolve(GM_getValue(k,d)); },
  setValue: function(k,v){ GM_setValue(k,v); return Promise.resolve(); }, deleteValue: function(k){ GM_deleteValue(k); return Promise.resolve(); },
  listValues: function(){ return Promise.resolve(GM_listValues()); }, xmlHttpRequest: GM_xmlhttpRequest, openInTab: GM_openInTab, setClipboard: GM_setClipboard };
try {
${s.source}
} catch (e) { console.error('[AgentMT userscript ${s.name.replace("'", "")}]', e); }
})();
""".trimIndent()
    }
}

class UserScriptStore(context: Context) {
    private val dir = File(context.filesDir, "userscripts").apply { mkdirs() }
    private val index = File(dir, "index.json")
    /** [UserScript.source] holds the code; where it was installed from lives in index.json. */
    private val origins = HashMap<String, String>()
    private val _scripts = MutableStateFlow(load())
    val scripts: StateFlow<List<UserScript>> = _scripts

    private fun load(): List<UserScript> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i); val id = o.getString("id"); origins[id] = o.optString("source")
            val code = File(dir, "$id.user.js").takeIf { it.exists() }?.readText() ?: return@mapNotNull null
            UserScriptMeta.parse(code, id, o.optString("source"), o.optBoolean("enabled", true))?.copy(source = code)
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<UserScript>) {
        index.writeText(JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("enabled", it.enabled).put("source", origins[it.id] ?: "")) } }.toString())
        _scripts.value = l
    }

    /** Installs or updates (same @name) a script. Throws when the code has no metadata block. */
    @Synchronized fun install(code: String, origin: String = ""): UserScript {
        val existing = _scripts.value.firstOrNull { UserScriptMeta.parse(code)?.name == it.name }
        val id = existing?.id ?: UUID.randomUUID().toString().take(12)
        val s = UserScriptMeta.parse(code, id) ?: error("This isn't a userscript (no // ==UserScript== header)")
        File(dir, "$id.user.js").writeText(code)
        origins[id] = origin
        val v = s.copy(source = code)
        save(_scripts.value.filterNot { it.id == id } + v)
        return v
    }

    @Synchronized fun setEnabled(id: String, on: Boolean) = save(_scripts.value.map { if (it.id == id) it.copy(enabled = on) else it })
    @Synchronized fun delete(id: String) { File(dir, "$id.user.js").delete(); save(_scripts.value.filterNot { it.id == id }) }

    fun forUrl(url: String, runAt: String? = null) = _scripts.value.filter { it.enabled && it.appliesTo(url) && (runAt == null || it.runAt == runAt) }
}
