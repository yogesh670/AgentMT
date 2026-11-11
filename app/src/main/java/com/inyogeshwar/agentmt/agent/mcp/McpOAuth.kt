package com.inyogeshwar.agentmt.agent.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.agent.llm.await
import com.inyogeshwar.agentmt.data.secure.SecretVault
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.1 (authorization code + PKCE) for remote MCP servers that advertise it
 * (RFC 9728 protected-resource metadata + RFC 8414 authorization-server metadata,
 * with dynamic client registration, RFC 7591). Tokens are stored encrypted in the
 * [SecretVault]; the browser redirects back to [REDIRECT_URI].
 *
 * A manual Bearer token set on the server always wins over this flow (see AgentEngine).
 */
class McpOAuth(private val context: Context, private val vault: SecretVault) {

    data class AuthEvent(val serverUrl: String, val ok: Boolean, val message: String)

    private data class Disc(val authEndpoint: String, val tokenEndpoint: String, val registrationEndpoint: String?, val scopes: String?)

    private data class Pending(
        val serverUrl: String,
        val verifier: String,
        val state: String,
        val clientId: String,
        val clientSecret: String?,
        val tokenEndpoint: String,
        val resource: String?,
    )

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    @Volatile private var pending: Pending? = null

    private fun key(url: String) = "mcp_oauth:$url"

    private fun load(url: String): JSONObject? = vault.get(key(url))?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun isLoggedIn(url: String): Boolean = load(url) != null

    fun logout(url: String) = vault.remove(key(url))

    /** A currently valid access token, refreshing it if it is near expiry. */
    suspend fun accessToken(url: String): String? {
        val o = load(url) ?: return null
        val exp = o.optLong("exp", 0L)
        if (exp > 0 && System.currentTimeMillis() > exp - 60_000 && o.optString("refresh").isNotBlank()) {
            refresh(url)?.let { return it }
        }
        return o.optString("access").takeIf { it.isNotBlank() }
    }

    private suspend fun refresh(url: String): String? {
        val o = load(url) ?: return null
        val rt = o.optString("refresh"); val token = o.optString("token")
        if (rt.isBlank() || token.isBlank()) return null
        val form = linkedMapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to o.optString("clientId"))
        o.optString("resource").takeIf { it.isNotBlank() }?.let { form["resource"] = it }
        val resp = runCatching { postForm(token, form, o.optString("clientSecret").takeIf { it.isNotBlank() }) }.getOrNull() ?: return null
        return store(url, o, resp)
    }

    /** Merges a token response into stored state and returns the new access token. */
    private fun store(url: String, prev: JSONObject, resp: JSONObject): String? {
        val access = resp.optString("access_token"); if (access.isBlank()) return null
        val refresh = resp.optString("refresh_token").ifBlank { prev.optString("refresh") }
        val expiresIn = resp.optLong("expires_in", 0L)
        val exp = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else prev.optLong("exp")
        vault.put(key(url), JSONObject(prev.toString()).put("access", access).put("refresh", refresh).put("exp", exp).toString())
        return access
    }

    /** Discovers endpoints, registers a client, then opens the authorization page in the browser. */
    suspend fun begin(serverUrl: String) {
        try {
            val disc = discover(serverUrl)
            val verifier = randomString(48)
            val state = randomString(24)
            var clientId: String? = null
            var clientSecret: String? = null
            disc.registrationEndpoint?.let { reg ->
                val body = JSONObject()
                    .put("client_name", "AgentMT")
                    .put("application_type", "native")
                    .put("redirect_uris", JSONArray().put(REDIRECT_URI))
                    .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("response_types", JSONArray().put("code"))
                    .put("token_endpoint_auth_method", "none")
                val r = postJson(reg, body)
                clientId = r.optString("client_id").takeIf { it.isNotBlank() }
                clientSecret = r.optString("client_secret").takeIf { it.isNotBlank() }
            }
            val cid = clientId ?: "agentmt"
            pending = Pending(serverUrl, verifier, state, cid, clientSecret, disc.tokenEndpoint, serverUrl)
            val authUrl = buildAuthUrl(disc.authEndpoint, cid, s256(verifier), state, disc.scopes)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _events.tryEmit(AuthEvent(serverUrl, true, "Opened the sign-in page in your browser."))
        } catch (e: Exception) {
            _events.tryEmit(AuthEvent(serverUrl, false, e.message ?: "OAuth failed"))
        }
    }

    /** Handles the browser redirect. Returns true if the URI belonged to this flow. */
    suspend fun handleRedirect(uri: Uri): Boolean {
        val p = pending ?: return false
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        pending = null
        if (error != null) {
            _events.tryEmit(AuthEvent(p.serverUrl, false, uri.getQueryParameter("error_description") ?: error)); return true
        }
        if (code.isNullOrBlank() || state != p.state) {
            _events.tryEmit(AuthEvent(p.serverUrl, false, "Sign-in response was invalid.")); return true
        }
        return try {
            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to p.clientId,
                "code_verifier" to p.verifier,
            )
            p.resource?.let { form["resource"] = it }
            val resp = postForm(p.tokenEndpoint, form, p.clientSecret)
            val base = load(p.serverUrl) ?: JSONObject()
            base.put("token", p.tokenEndpoint).put("clientId", p.clientId)
                .put("clientSecret", p.clientSecret ?: "").put("resource", p.resource ?: "")
            val access = store(p.serverUrl, base, resp)
            _events.tryEmit(AuthEvent(p.serverUrl, access != null, if (access != null) "Signed in." else "No access token in response."))
            true
        } catch (e: Exception) {
            _events.tryEmit(AuthEvent(p.serverUrl, false, e.message ?: "Token exchange failed")); true
        }
    }

    private suspend fun discover(serverUrl: String): Disc {
        val origin = runCatching { Uri.parse(serverUrl) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }
            ?: throw McpException("Invalid server URL")
        var asUrl: String? = null
        for (u in listOf("$origin/.well-known/oauth-protected-resource", serverUrl.trimEnd('/') + "/.well-known/oauth-protected-resource")) {
            val j = getJsonOrNull(u) ?: continue
            val arr = j.optJSONArray("authorization_servers")
            if (arr != null && arr.length() > 0) { asUrl = arr.optString(0).takeIf { it.isNotBlank() }; break }
        }
        for (asServer in listOfNotNull(asUrl, origin).distinct()) {
            val asOrigin = runCatching { Uri.parse(asServer) }.getOrNull()?.let { "${it.scheme}://${it.authority}" } ?: asServer
            for (u in listOf(
                "$asServer/.well-known/oauth-authorization-server",
                "$asServer/.well-known/openid-configuration",
                "$asOrigin/.well-known/oauth-authorization-server",
                "$asOrigin/.well-known/openid-configuration",
            )) {
                val j = getJsonOrNull(u) ?: continue
                val auth = j.optString("authorization_endpoint").takeIf { it.isNotBlank() } ?: continue
                val token = j.optString("token_endpoint").takeIf { it.isNotBlank() } ?: continue
                return Disc(auth, token, j.optString("registration_endpoint").takeIf { it.isNotBlank() },
                    j.optString("scopes_supported").takeIf { it.isNotBlank() })
            }
        }
        throw McpException("This server did not advertise OAuth endpoints")
    }

    private fun buildAuthUrl(authEndpoint: String, clientId: String, challenge: String, state: String, scopes: String?): String {
        val b = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
        scopes?.takeIf { it.isNotBlank() }?.let { b.appendQueryParameter("scope", it) }
        return b.build().toString()
    }

    private suspend fun getJsonOrNull(url: String): JSONObject? = runCatching {
        Http.client.newCall(Http.get(url, mapOf("Accept" to "application/json"))).await().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) }
        }
    }.getOrNull()

    private suspend fun postJson(url: String, body: JSONObject): JSONObject = Http.client.newCall(
        Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").build()
    ).await().use { r ->
        val s = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw McpException("Registration failed: HTTP ${r.code}")
        JSONObject(s)
    }

    private suspend fun postForm(url: String, form: Map<String, String>, clientSecret: String?): JSONObject {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        clientSecret?.takeIf { it.isNotBlank() }?.let { fb.add("client_secret", it) }
        return Http.client.newCall(
            Request.Builder().url(url).post(fb.build()).header("Accept", "application/json").build()
        ).await().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw McpException("Token endpoint: HTTP ${r.code} ${s.take(160)}")
            JSONObject(s)
        }
    }

    private fun randomString(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun s256(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT_URI = "agentmt://oauth/mcp"
        const val SCHEME = "agentmt"
        const val HOST = "oauth"
    }
}
