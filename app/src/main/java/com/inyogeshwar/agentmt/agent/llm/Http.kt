package com.inyogeshwar.agentmt.agent.llm

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Callback
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    fun post(url: String, body: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).post(body.toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    fun get(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
}

/** Executes a call and cancels it if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

/** Reads `data:` lines of a server-sent-events body, calling [onData] for each payload. */
inline fun Response.forEachSse(onEvent: (event: String?, data: String) -> Unit) {
    val src = body!!.source()
    var event: String? = null
    val buf = StringBuilder()
    while (true) {
        val line = src.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (buf.isNotEmpty()) { onEvent(event, buf.toString()); buf.clear() }
                event = null
            }
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> { if (buf.isNotEmpty()) buf.append('\n'); buf.append(line.substring(5).trimStart()) }
        }
    }
    if (buf.isNotEmpty()) onEvent(event, buf.toString())
}
