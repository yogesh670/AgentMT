package com.inyogeshwar.agentmt.feature.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * One shared in-app browser. The user browses and signs in on the Browser screen; the agent drives the same
 * WebView (same cookies, same logins) through the browser tools, even while the screen is closed.
 */
class BrowserController(private val context: Context, val scripts: UserScriptStore) {
    private var web: WebView? = null
    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val canGoBack = MutableStateFlow(false)
    val canGoForward = MutableStateFlow(false)
    val desktop = MutableStateFlow(false)
    /** Last thing the agent did, shown as a banner on the Browser screen. */
    val agentAction = MutableStateFlow<String?>(null)
    @Volatile private var loading = false
    private var mobileUa: String = ""

    /** Set by the visible Browser screen to open the system file picker for <input type=file>. */
    var fileChooser: ((ValueCallback<Array<Uri>>, Intent) -> Boolean)? = null
    /** Set by the Browser screen: a link to a .user.js was tapped (Greasy Fork's Install button). */
    var onUserScriptLink: ((String) -> Unit)? = null
    /** Set by the Browser screen for downloads. */
    var onDownload: ((String) -> Unit)? = null

    val created get() = web != null

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView {
        web?.let { return it }
        val w = WebView(context.applicationContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }
        mobileUa = w.settings.userAgentString.replace("; wv", "")
        w.settings.userAgentString = mobileUa
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.substringBefore('?').endsWith(".user.js")) { onUserScriptLink?.let { it(u); return true } }
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return false
                // intent://, market://, tel:, mailto: … hand to Android
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                loading = true; url.value = u; nav(view)
                scripts.forUrl(u, "document-start").forEach { view.evaluateJavascript(UserScriptMeta.wrap(it), null) }
            }
            override fun onPageFinished(view: WebView, u: String) {
                loading = false; url.value = u; title.value = view.title.orEmpty(); nav(view)
                scripts.forUrl(u).filter { it.runAt != "document-start" }.forEach { view.evaluateJavascript(UserScriptMeta.wrap(it), null) }
                CookieManager.getInstance().flush()
            }
            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) { url.value = u; nav(view) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress.value = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title.value = t.orEmpty() }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val f = fileChooser ?: run { cb.onReceiveValue(null); return true }
                return f(cb, params.createIntent())
            }
        }
        w.setDownloadListener { u, _, _, _, _ -> onDownload?.invoke(u) ?: runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        detachedLayout(w)
        web = w
        return w
    }

    private fun nav(v: WebView) { canGoBack.value = v.canGoBack(); canGoForward.value = v.canGoForward() }

    /** A WebView that isn't on screen still needs a size so pages lay out and elements can be clicked. */
    fun detachedLayout(target: WebView? = web) {
        val w = target ?: return
        if (w.parent != null) return
        val dm = context.resources.displayMetrics
        val wPx = dm.widthPixels.coerceAtLeast(720); val hPx = dm.heightPixels.coerceAtLeast(1280)
        w.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, wPx, hPx)
    }

    fun detach() { web?.let { (it.parent as? ViewGroup)?.removeView(it); detachedLayout(it) } }

    fun setDesktop(on: Boolean) {
        val w = view(); desktop.value = on
        w.settings.userAgentString = if (on) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36" else mobileUa
        w.reload()
    }

    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        web?.clearCache(true); web?.clearHistory()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    // ---------------- agent API (call from any thread) ----------------

    suspend fun open(raw: String): String {
        val target = normalizeUrl(raw)
        withContext(Dispatchers.Main) { agentAction.value = "Opening $target"; loading = true; view().loadUrl(target) }
        waitForLoad()
        return snapshot()
    }

    suspend fun back(): String { withContext(Dispatchers.Main) { view().takeIf { it.canGoBack() }?.goBack() }; waitForLoad(); return snapshot() }

    suspend fun snapshot(maxText: Int = 12_000): String {
        val raw = eval(SNAPSHOT_JS.replace("MAXT", maxText.toString()))
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return "Page not readable yet (${url.value})."
        return buildString {
            append("URL: ").append(o.optString("url")).append('\n')
            append("Title: ").append(o.optString("title")).append("\n\n")
            append("# Page text\n").append(o.optString("text").ifBlank { "(no text)" }).append("\n\n")
            append("# Interactive elements (use the number with browser click / type)\n").append(o.optString("elements").ifBlank { "(none)" })
        }
    }

    suspend fun click(index: Int): String {
        withContext(Dispatchers.Main) { agentAction.value = "Clicking element $index" }
        val r = eval("""(function(){var e=document.querySelector('[data-agentmt-id="$index"]'); if(!e) return 'missing';
            e.scrollIntoView({block:'center'}); if(e.focus) e.focus(); e.click(); return 'ok';})()""")
        if (r != "ok") return "No element [$index] on this page. Call browser_read to get fresh numbers."
        delay(400); waitForLoad(8_000)
        return snapshot()
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String {
        withContext(Dispatchers.Main) { agentAction.value = "Typing into element $index" }
        val v = JSONObject.quote(text)
        val r = eval("""(function(){var e=document.querySelector('[data-agentmt-id="$index"]'); if(!e) return 'missing';
            e.scrollIntoView({block:'center'}); if(e.focus) e.focus(); var v=$v;
            if(e.isContentEditable){ e.textContent=v; } else { var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value'); if(d&&d.set) d.set.call(e,v); else e.value=v; }
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            if(${submit}){ var f=e.form; if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); }
              else { ['keydown','keypress','keyup'].forEach(function(t){ e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true})); }); } }
            return 'ok';})()""")
        if (r != "ok") return "No element [$index] on this page. Call browser_read to get fresh numbers."
        if (submit) { delay(500); waitForLoad(10_000) }
        return snapshot()
    }

    suspend fun scroll(down: Boolean): String {
        eval("window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight*0.8)); 'ok'")
        delay(300)
        return snapshot()
    }

    suspend fun runJs(code: String): String {
        withContext(Dispatchers.Main) { agentAction.value = "Running a script on the page" }
        val wrapped = "(function(){ try { var __r = (function(){ $code })(); return typeof __r === 'string' ? __r : JSON.stringify(__r); } catch(e) { return 'Error: ' + e; } })()"
        return eval(wrapped).ifEmpty { "(no result)" }.take(20_000)
    }

    suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        val w = view()
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                w.evaluateJavascript(js) { res -> if (cont.isActive) cont.resume(decode(res)) }
            }
        } ?: ""
    }

    private suspend fun waitForLoad(timeout: Long = 20_000) {
        withTimeoutOrNull(timeout) {
            delay(250)
            while (loading || progress.value < 100) delay(150)
            // Let client-side apps render.
            repeat(20) { if (eval("document.readyState") == "complete") return@withTimeoutOrNull; delay(150) }
        }
        delay(400)
    }

    companion object {
        fun normalizeUrl(raw: String): String {
            val t = raw.trim()
            return when {
                t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
                t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
                else -> "https://$t"
            }
        }

        /** evaluateJavascript returns a JSON value; strings come back quoted. */
        fun decode(res: String?): String {
            if (res == null || res == "null" || res == "undefined") return ""
            return if (res.startsWith("\"")) runCatching { JSONArray("[$res]").getString(0) }.getOrDefault(res) else res
        }

        private val SNAPSHOT_JS = """
(function(){
 var sel='a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=option],[onclick],[contenteditable=""],[contenteditable=true]';
 document.querySelectorAll('[data-agentmt-id]').forEach(function(e){e.removeAttribute('data-agentmt-id');});
 var out=[],n=0,els=document.querySelectorAll(sel);
 for(var i=0;i<els.length&&n<150;i++){ var e=els[i]; var r=e.getBoundingClientRect(); var st=getComputedStyle(e);
  if((r.width<2&&r.height<2)||st.visibility==='hidden'||st.display==='none'||e.disabled) continue;
  n++; e.setAttribute('data-agentmt-id',n);
  var tag=e.tagName.toLowerCase(), ty=e.type&&tag==='input'?(' type='+e.type):'';
  var label=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.name||'').trim().replace(/\s+/g,' ').slice(0,80);
  if(tag==='input'&&e.value&&e.type!=='password') label+=' (value: '+String(e.value).slice(0,40)+')';
  if(e.checked) label+=' [checked]';
  var href=(tag==='a'&&e.href)?(' -> '+e.href.slice(0,120)):'';
  out.push('['+n+'] '+tag+ty+' "'+label+'"'+href);
 }
 var text=(document.body?document.body.innerText:'').replace(/[ \t]+/g,' ').replace(/\n\s*\n+/g,'\n').slice(0,MAXT);
 return JSON.stringify({title:document.title,url:location.href,text:text,elements:out.join('\n')});
})()
""".trimIndent()
    }
}
