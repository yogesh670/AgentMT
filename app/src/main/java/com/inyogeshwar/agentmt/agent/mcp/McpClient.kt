package com.inyogeshwar.agentmt.agent.mcp

import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.agent.llm.await
import com.inyogeshwar.agentmt.agent.llm.forEachSse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class McpToolDef(val name: String, val description: String, val inputSchema: JSONObject)

/**
 * Minimal MCP client. Streamable HTTP (JSON or SSE responses) for http(s) URLs; for "linux:<command>" it runs the command
 * inside the installed Linux and talks newline-delimited JSON-RPC over stdin/stdout (the stdio transport, e.g. `rea mcp`).
 */
class McpClient(private val configured: String, private val token: String?) {
    private var url: String = configured
    private val stdio: StdioMcp? = if (configured.startsWith(LINUX_PREFIX)) StdioMcp.get(configured.removePrefix(LINUX_PREFIX).trim()) else null
    private var sessionId: String? = null
    private val ids = AtomicInteger(1)
    var serverName: String? = null; private set

    private fun headers() = buildMap {
        put("Accept", "application/json, text/event-stream")
        put("MCP-Protocol-Version", PROTOCOL)
        token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        sessionId?.let { put("Mcp-Session-Id", it) }
    }

    private suspend fun rpc(method: String, params: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val id = ids.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).apply { params?.let { put("params", it) } }
        stdio?.let { io ->
            val resp = io.request(msg, if (method == "initialize") 60_000 else 600_000)
            resp.optJSONObject("error")?.let { throw McpException(it.optString("message", "MCP error")) }
            return@withContext resp.optJSONObject("result") ?: JSONObject()
        }
        Http.client.newCall(Http.post(url, msg.toString(), headers())).await().use { r ->
            r.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!r.isSuccessful) throw McpException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}")
            val resp = readResponse(r, id) ?: throw McpException("No response for $method")
            resp.optJSONObject("error")?.let { throw McpException(it.optString("message", "MCP error")) }
            resp.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun readResponse(r: Response, id: Int): JSONObject? {
        val type = r.header("Content-Type").orEmpty()
        if (type.contains("text/event-stream")) {
            var found: JSONObject? = null
            r.forEachSse { _, data ->
                if (found == null) runCatching { JSONObject(data) }.getOrNull()?.let { if (it.optInt("id", -1) == id) found = it }
            }
            return found
        }
        val s = r.body?.string().orEmpty()
        if (s.isBlank()) return null
        return if (s.trimStart().startsWith("[")) JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("id") == id } }
        else JSONObject(s)
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
        stdio?.let { it.send(msg); return@withContext }
        runCatching { Http.client.newCall(Http.post(url, msg.toString(), headers())).await().close() }
    }

    suspend fun connect() {
        if (sessionId != null || serverName != null) return
        stdio?.initialized?.let { serverName = it; return }
        val res = rpc("initialize", JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "AgentMT").put("version", "0.2.0")))
        serverName = res.optJSONObject("serverInfo")?.optString("name") ?: "MCP server"
        notify("notifications/initialized")
        stdio?.initialized = serverName
    }

    suspend fun listTools(): List<McpToolDef> {
        connect()
        val out = ArrayList<McpToolDef>(); var cursor: String? = null
        do {
            val res = rpc("tools/list", cursor?.let { JSONObject().put("cursor", it) })
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                out += McpToolDef(t.getString("name"), t.optString("description"),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()))
            }
            cursor = res.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null && out.size < 500)
        return out
    }

    /** Returns text content and whether the server flagged an error. */
    suspend fun callTool(name: String, arguments: JSONObject): Pair<String, Boolean> {
        connect()
        val res = rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        val content = res.optJSONArray("content") ?: JSONArray()
        val text = (0 until content.length()).joinToString("\n") { i ->
            val c = content.getJSONObject(i)
            when (c.optString("type")) {
                "text" -> c.optString("text")
                "resource" -> c.optJSONObject("resource")?.optString("text") ?: "[resource]"
                "image" -> "[image ${c.optString("mimeType")}]"
                else -> c.toString().take(500)
            }
        }.ifEmpty { res.optJSONObject("structuredContent")?.toString() ?: "(no content)" }
        return text to res.optBoolean("isError", false)
    }

    companion object { const val PROTOCOL = "2025-06-18"; const val LINUX_PREFIX = "linux:" }
}

class McpException(message: String) : Exception(message)


/** One long-lived Linux process per command, shared by every turn so the server starts once. */
class StdioMcp private constructor(private val command: String) {
    @Volatile var initialized: String? = null
    private var proc: Process? = null
    private var out: java.io.BufferedWriter? = null
    private var input: java.io.BufferedReader? = null
    private val lock = Any()
    private val stderr = StringBuffer()

    private fun ensure() {
        if (proc?.isAlive == true) return
        initialized = null
        val linux = com.inyogeshwar.agentmt.core.linux.LinuxEnv
        if (!linux.ready) throw McpException("Install Linux first (Menu → Linux) to run local MCP servers")
        val p = linux.process(java.io.File(linux.hostPath("/root").path.let { if (java.io.File(it).isDirectory) it else "/" }), command).start()
        proc = p
        out = p.outputStream.bufferedWriter()
        input = p.inputStream.bufferedReader()
        stderr.setLength(0)
        Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (stderr.length < 4000) stderr.append(it).append('\n') } } }.start()
    }

    fun send(msg: JSONObject) = synchronized(lock) { ensure(); out!!.write(msg.toString()); out!!.write("\n"); out!!.flush() }

    /** Sends a request and reads lines until its response; answers server pings, skips notifications and log noise. */
    fun request(msg: JSONObject, timeoutMs: Long): JSONObject = synchronized(lock) {
        ensure()
        out!!.write(msg.toString()); out!!.write("\n"); out!!.flush()
        val id = msg.getInt("id")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = input!!.readLine() ?: run {
                proc?.destroy()
                throw McpException("Server exited. " + stderr.toString().takeLast(600))
            }
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (o.has("method") && o.has("id")) { // server → client request (ping, roots/list…)
                val reply = JSONObject().put("jsonrpc", "2.0").put("id", o.get("id"))
                if (o.optString("method") == "ping") reply.put("result", JSONObject())
                else if (o.optString("method") == "roots/list") reply.put("result", JSONObject().put("roots", org.json.JSONArray()))
                else reply.put("error", JSONObject().put("code", -32601).put("message", "Not supported"))
                out!!.write(reply.toString()); out!!.write("\n"); out!!.flush(); continue
            }
            if (o.optInt("id", -1) == id) return o
        }
        throw McpException("Timed out waiting for the server")
    }

    fun stop() { runCatching { proc?.destroy() }; proc = null; initialized = null }

    companion object {
        private val all = java.util.concurrent.ConcurrentHashMap<String, StdioMcp>()
        fun get(command: String): StdioMcp = all.getOrPut(command) { StdioMcp(command) }
        fun stopAll() { all.values.forEach { it.stop() } }
    }
}

