package com.inyogeshwar.agentmt.agent.tools

import com.inyogeshwar.agentmt.feature.browser.BrowserController
import org.json.JSONObject

/** The in-app browser the agent shares with the user. Set by AppContainer. */
object BrowserHub { @Volatile var controller: BrowserController? = null }

private fun ctl() = BrowserHub.controller ?: error("Browser is not available")

/** Reads the current page. Read-only, so it runs without approval in the default mode. */
class BrowserReadTool : Tool {
    override val name = "browser_read"
    override val kind = ToolKind.Read
    override val description = "Read the page open in the built-in browser: URL, title, visible text and numbered interactive elements " +
        "(links, buttons, inputs). Numbers change after every navigation, so read again before clicking."
    override val schema = schema(p("max_chars", "integer", "Max page text, default 12000"))
    override fun target(args: JSONObject) = BrowserHub.controller?.url?.value.orEmpty()
    override fun summary(args: JSONObject) = "Read browser page"
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val c = ctl()
        if (!c.created || c.url.value.isBlank()) return ToolResult("The browser has no page open. Use browser with action open first.")
        return ToolResult(c.snapshot(args.optInt("max_chars", 12_000).coerceIn(1_000, 40_000)))
    }
}

/** Drives the browser: open, click, type, scroll, back, run_js. Uses the user's logged-in sessions. */
class BrowserTool : Tool {
    override val name = "browser"
    override val kind = ToolKind.Network
    override val description = "Control the built-in browser (a real Chrome WebView with the user's cookies and logins, JavaScript on). " +
        "Actions: open (url or search words), click (index), type (index, text, submit), scroll (down|up), back, run_js (code: a function body that returns a value). " +
        "Every action returns the page after it. Use this for sites that need JavaScript or a login; use web_fetch for plain pages. " +
        "If a site needs the user to sign in or solve a captcha, ask them to open Browser from the menu, do it, then tell you to continue. " +
        "Never type passwords or payment details yourself unless the user gave them for this purpose."
    override val schema: JSONObject = JSONObject("""{"type":"object","properties":{
        "action":{"type":"string","enum":["open","click","type","scroll","back","run_js"]},
        "url":{"type":"string","description":"For open: URL or search words"},
        "index":{"type":"integer","description":"Element number from the last page read"},
        "text":{"type":"string","description":"For type: text to enter"},
        "submit":{"type":"boolean","description":"For type: press Enter / submit the form after typing"},
        "direction":{"type":"string","enum":["down","up"]},
        "code":{"type":"string","description":"For run_js: JavaScript function body, e.g. return document.title"}
        },"required":["action"]}""")
    override fun target(args: JSONObject) = when (args.optString("action")) {
        "open" -> args.optString("url")
        "run_js" -> "js:" + args.optString("code").take(60)
        else -> BrowserHub.controller?.url?.value.orEmpty()
    }
    override fun summary(args: JSONObject) = when (args.optString("action")) {
        "open" -> "Opened ${args.optString("url").take(80)}"
        "click" -> "Clicked [${args.optInt("index")}]"
        "type" -> "Typed into [${args.optInt("index")}]"
        "scroll" -> "Scrolled ${args.optString("direction", "down")}"
        "back" -> "Went back"
        "run_js" -> "Ran script on page"
        else -> "Browser"
    }
    override fun preview(args: JSONObject, ctx: ToolContext): String? = when (args.optString("action")) {
        "type" -> "Type into [${args.optInt("index")}]: " + args.optString("text").take(200) + if (args.optBoolean("submit")) "\n(then submit)" else ""
        "run_js" -> args.optString("code").take(2000)
        "open" -> args.optString("url")
        else -> null
    }
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val c = ctl()
        val out = when (args.optString("action")) {
            "open" -> args.optString("url").takeIf { it.isNotBlank() }?.let { c.open(it) } ?: return ToolResult("open needs url", true)
            "click" -> if (args.has("index")) c.click(args.getInt("index")) else return ToolResult("click needs index", true)
            "type" -> if (args.has("index")) c.type(args.getInt("index"), args.optString("text"), args.optBoolean("submit")) else return ToolResult("type needs index", true)
            "scroll" -> c.scroll(args.optString("direction", "down") != "up")
            "back" -> c.back()
            "run_js" -> c.runJs(args.optString("code"))
            else -> return ToolResult("Unknown action. Use open, click, type, scroll, back or run_js.", true)
        }
        return ToolResult(out)
    }
}
