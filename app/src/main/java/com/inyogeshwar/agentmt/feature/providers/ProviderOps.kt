package com.inyogeshwar.agentmt.feature.providers

import com.inyogeshwar.agentmt.agent.ProviderPreset
import com.inyogeshwar.agentmt.agent.clientFor
import com.inyogeshwar.agentmt.agent.llm.Http
import com.inyogeshwar.agentmt.agent.llm.ModelInfo
import com.inyogeshwar.agentmt.agent.llm.await
import com.inyogeshwar.agentmt.agent.routing.ModelMeta
import com.inyogeshwar.agentmt.agent.routing.encodeMeta
import com.inyogeshwar.agentmt.agent.routing.meta
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.data.db.CredentialEntity
import com.inyogeshwar.agentmt.data.db.ProviderEntity
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

object ProviderOps {
    suspend fun addKey(c: AppContainer, providerId: String, label: String, key: String): CredentialEntity {
        val id = UUID.randomUUID().toString()
        c.vault.put("cred:$id", key.trim())
        val cred = CredentialEntity(id, providerId, label.trim().ifEmpty { "Key ${c.db.credentials().maxPriority(providerId) + 2}" },
            c.db.credentials().maxPriority(providerId) + 1, createdAt = System.currentTimeMillis())
        c.db.credentials().upsert(cred)
        return cred
    }

    suspend fun deleteKey(c: AppContainer, cred: CredentialEntity) { c.db.credentials().delete(cred.id); c.vault.remove("cred:${cred.id}") }

    suspend fun deleteProvider(c: AppContainer, id: String) {
        c.db.credentials().listFor(id).forEach { c.vault.remove("cred:${it.id}") }
        c.db.credentials().deleteFor(id); c.db.providers().delete(id)
        val s = c.settings.state.value
        if (s.defaultProviderId == id) c.settings.update { it.copy(defaultProviderId = null, defaultModelId = null) }
    }

    /** Fetches the catalog with [key] (or the provider's first key) and merges capability facts into modelMeta. */
    suspend fun fetchModels(c: AppContainer, p: ProviderEntity, key: String?): List<ModelInfo> {
        val k = key ?: c.db.credentials().listFor(p.id).firstOrNull { it.enabled }?.let { c.vault.get("cred:${it.id}") }
        val list = clientFor(p, k).listModels()
        if (list.isNotEmpty()) {
            val meta = p.meta().toMutableMap()
            list.forEach { mi ->
                val old = meta[mi.id]
                meta[mi.id] = ModelMeta(old?.alias, mi.vision ?: old?.vision, mi.tools ?: old?.tools, mi.contextLength ?: old?.ctx)
            }
            c.db.providers().get(p.id)?.let { c.db.providers().upsert(it.copy(modelMeta = encodeMeta(meta))) }
        }
        return list
    }

    /** Validates a key with a cheap catalog request; never sends user content. */
    suspend fun testKey(c: AppContainer, p: ProviderEntity, cred: CredentialEntity): Result<Int> = runCatching {
        clientFor(p, c.vault.get("cred:${cred.id}")).listModels().size
    }.also { r ->
        c.db.credentials().upsert(cred.copy(status = if (r.isSuccess) "ok" else if ((r.exceptionOrNull()?.message ?: "").contains("401") || (r.exceptionOrNull()?.message ?: "").contains("403")) "invalid" else "error",
            lastError = r.exceptionOrNull()?.message?.take(120), cooldownUntil = if (r.isSuccess) 0 else cred.cooldownUntil))
    }

    fun fromPreset(pr: ProviderPreset, id: String = if (pr.id == "custom") "custom-" + UUID.randomUUID().toString().take(6) else pr.id) =
        ProviderEntity(id, pr.name.removeSuffix(" (OpenAI-compatible)"), pr.kind, pr.baseUrl, pr.suggestedModels.joinToString("\n"), pr.suggestedModels.firstOrNull())

    /** Probes common local servers (Ollama, LM Studio, llama.cpp) for an OpenAI-compatible endpoint. */
    suspend fun detectLocal(): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()
        for ((name, url) in listOf("Ollama" to "http://127.0.0.1:11434/v1", "LM Studio" to "http://127.0.0.1:1234/v1", "llama.cpp server" to "http://127.0.0.1:8080/v1")) {
            val ok = withTimeoutOrNull(1500) {
                runCatching { Http.client.newCall(Http.get("$url/models", emptyMap())).await().use { it.isSuccessful } }.getOrDefault(false)
            } ?: false
            if (ok) found += name to url
        }
        return found
    }
}
