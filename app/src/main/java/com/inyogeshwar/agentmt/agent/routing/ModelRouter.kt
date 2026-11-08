package com.inyogeshwar.agentmt.agent.routing

import com.inyogeshwar.agentmt.agent.clientFor
import com.inyogeshwar.agentmt.agent.llm.LlmEvent
import com.inyogeshwar.agentmt.agent.llm.LlmRequest
import com.inyogeshwar.agentmt.agent.modelList
import com.inyogeshwar.agentmt.data.db.AppDatabase
import com.inyogeshwar.agentmt.data.db.CredentialEntity
import com.inyogeshwar.agentmt.data.db.ProviderEntity
import com.inyogeshwar.agentmt.data.db.SessionEntity
import com.inyogeshwar.agentmt.data.prefs.SettingsStore
import com.inyogeshwar.agentmt.data.secure.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ModelMeta(val alias: String? = null, val vision: Boolean? = null, val tools: Boolean? = null, val ctx: Int? = null)

fun ProviderEntity.meta(): Map<String, ModelMeta> = runCatching {
    val o = JSONObject(modelMeta)
    o.keys().asSequence().associateWith { k ->
        val m = o.getJSONObject(k)
        ModelMeta(m.optString("alias").ifBlank { null }, if (m.has("vision")) m.optBoolean("vision") else null,
            if (m.has("tools")) m.optBoolean("tools") else null, m.optInt("ctx", 0).takeIf { it > 0 })
    }
}.getOrDefault(emptyMap())

fun encodeMeta(map: Map<String, ModelMeta>): String = JSONObject().apply {
    map.forEach { (k, m) ->
        val o = JSONObject()
        m.alias?.let { o.put("alias", it) }; m.vision?.let { o.put("vision", it) }; m.tools?.let { o.put("tools", it) }; m.ctx?.let { o.put("ctx", it) }
        if (o.length() > 0) put(k, o)
    }
}.toString()

data class ProfileCandidate(val providerId: String, val model: String, val credentialId: String?)

fun parseCandidates(json: String): List<ProfileCandidate> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { a.getJSONObject(it) }.map {
        ProfileCandidate(it.getString("providerId"), it.getString("model"), it.optString("credentialId").ifBlank { null })
    }
}.getOrDefault(emptyList())

fun encodeCandidates(list: List<ProfileCandidate>): String = JSONArray().apply {
    list.forEach { put(JSONObject().put("providerId", it.providerId).put("model", it.model).apply { it.credentialId?.let { c -> put("credentialId", c) } }) }
}.toString()

fun dayKey(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

/** Turns the session's choice (pinned model or profile) into concrete candidates and runs them with fallback. */
class ModelRouter(private val db: AppDatabase, private val vault: SecretVault, private val settings: SettingsStore) {

    /** Gives each provider without credentials a default one from the legacy single-key slot (v0.2 → v0.3). */
    suspend fun migrateLegacyKeys() {
        for (p in db.providers().list()) {
            if (db.credentials().listFor(p.id).isNotEmpty()) continue
            val legacy = vault.get("provider:${p.id}")
            val id = UUID.randomUUID().toString()
            if (legacy != null) vault.put("cred:$id", legacy)
            db.credentials().upsert(CredentialEntity(id, p.id, "Main", 0, createdAt = System.currentTimeMillis()))
            vault.remove("provider:${p.id}")
        }
    }

    private suspend fun expand(p: ProviderEntity, model: String, credentialId: String?, rotate: Boolean): List<Candidate> {
        val meta = p.meta()[model]
        val creds = db.credentials().listFor(p.id).filter { it.enabled }
        val chosen = when {
            credentialId != null -> creds.filter { it.id == credentialId }
            rotate -> creds
            else -> creds.take(1)
        }
        if (chosen.isEmpty()) return listOf(Candidate(p.id, p.name, model, null, null, vision = meta?.vision, tools = meta?.tools))
        return chosen.map { c ->
            Candidate(p.id, p.name, model, c.id, c.label, c.cooldownUntil, c.status == "invalid", meta?.vision, meta?.tools)
        }
    }

    suspend fun candidatesFor(session: SessionEntity): List<Candidate> {
        val s = settings.state.value
        val providers = db.providers().list().filter { it.enabled }.associateBy { it.id }
        val out = ArrayList<Candidate>()
        val profileId = session.profileId ?: if (session.providerId == null) s.defaultProfileId else null
        val profile = profileId?.let { db.routing().get(it) }
        if (profile != null) {
            for (pc in parseCandidates(profile.candidatesJson)) {
                val p = providers[pc.providerId] ?: continue
                out += expand(p, pc.model, pc.credentialId, s.rotateKeys)
                if (profile.mode == "pinned") break
            }
        } else {
            val p = (session.providerId ?: s.defaultProviderId)?.let { providers[it] } ?: providers.values.firstOrNull()
            val model = session.modelId ?: p?.defaultModel ?: s.defaultModelId ?: p?.modelList()?.firstOrNull()
            if (p != null && model != null) out += expand(p, model, null, s.rotateKeys)
            // global fallback profile after the pinned model
            s.fallbackProfileId?.let { db.routing().get(it) }?.let { fp ->
                for (pc in parseCandidates(fp.candidatesJson)) {
                    val fpP = providers[pc.providerId] ?: continue
                    out += expand(fpP, pc.model, pc.credentialId, s.rotateKeys)
                }
            }
        }
        return out.distinctBy { Triple(it.providerId, it.model, it.credentialId) }
    }

    /** Streams [req] (its model field is replaced per candidate). Returns the candidate that answered. */
    suspend fun stream(
        candidates: List<Candidate>,
        req: LlmRequest,
        needsVision: Boolean,
        onNote: suspend (String) -> Unit,
        onEvent: suspend (LlmEvent) -> Unit,
    ): Candidate {
        val s = settings.state.value
        val providers = db.providers().list().associateBy { it.id }
        var inTok = 0L; var outTok = 0L
        val runner = RouteRunner(maxAttempts = s.maxAttempts.coerceIn(1, 8))
        return runner.run(
            candidates, needsVision, needsTools = req.tools.isNotEmpty(),
            attempt = { c, emit ->
                val p = providers[c.providerId] ?: throw IllegalStateException("Provider removed")
                val key = c.credentialId?.let { vault.get("cred:$it") }
                inTok = 0; outTok = 0
                clientFor(p, key).stream(req.copy(model = c.model)).collect { ev ->
                    if (ev is LlmEvent.Usage) { inTok += ev.input; outTok += ev.output }
                    emit(ev)
                }
            },
            hooks = RouteRunner.Hooks(
                onEvent = onEvent,
                onNote = onNote,
                onFailure = { c, f -> recordFailure(c, f) },
                onSuccess = { c, ms -> recordSuccess(c, ms, inTok, outTok) },
            ),
        )
    }

    private suspend fun recordFailure(c: Candidate, f: Failure) {
        val now = System.currentTimeMillis()
        c.credentialId?.let { id ->
            db.credentials().get(id)?.let { cred ->
                db.credentials().upsert(cred.copy(
                    failures = cred.failures + 1, lastUsedAt = now, lastError = "${f.label}${f.code?.let { " ($it)" } ?: ""}",
                    status = when (f.kind) {
                        FailureKind.AUTH -> "invalid"; FailureKind.RATE_LIMIT -> "rate_limited"; FailureKind.NO_CREDIT -> "no_credit"; else -> "error"
                    },
                    cooldownUntil = if (f.cooldownMs > 0) now + f.cooldownMs else cred.cooldownUntil,
                ))
            }
        }
        db.usage().add(dayKey(now), c.credentialId ?: c.providerId, c.model, c.providerId, ok = false, inTok = 0, outTok = 0)
    }

    private suspend fun recordSuccess(c: Candidate, latency: Long, inTok: Long, outTok: Long) {
        val now = System.currentTimeMillis()
        c.credentialId?.let { id ->
            db.credentials().get(id)?.let { cred ->
                db.credentials().upsert(cred.copy(requests = cred.requests + 1, lastUsedAt = now, status = "ok", lastError = null,
                    cooldownUntil = 0, inputTokens = cred.inputTokens + inTok, outputTokens = cred.outputTokens + outTok, lastLatencyMs = latency))
            }
        }
        db.usage().add(dayKey(now), c.credentialId ?: c.providerId, c.model, c.providerId, ok = true, inTok = inTok, outTok = outTok)
    }

    suspend fun resetCredential(id: String) {
        db.credentials().get(id)?.let { db.credentials().upsert(it.copy(status = "unknown", cooldownUntil = 0, lastError = null)) }
    }
}
