package com.inyogeshwar.agentmt.agent

import android.content.Context
import org.json.JSONArray

/** One model row from the catalog: [id, name, context, tools(0/1), in $/M, out $/M, cache read $/M, cache write $/M, max output]. */
data class ModelInfo(
    val id: String, val name: String, val context: Int, val tools: Boolean,
    val inPrice: Double, val outPrice: Double, val cacheRead: Double, val cacheWrite: Double, val maxOutput: Int,
) {
    fun priceLabel(): String = if (inPrice == 0.0 && outPrice == 0.0) "free" else "\$${fmt(inPrice)}/\$${fmt(outPrice)} per M"
    fun contextLabel(): String = if (context >= 1_000_000) "${context / 1_000_000}M" else "${context / 1000}k"
    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0')
}

data class CatalogProvider(val id: String, val name: String, val kind: String, val api: String, val env: String, val models: List<ModelInfo>)

/** Bundled provider/model catalog (assets/catalog/providers.json): 200+ providers with model context, tool support and prices. */
object ProviderCatalog {
    @Volatile private var loaded: List<CatalogProvider>? = null
    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    val providers: List<CatalogProvider> get() = loaded ?: synchronized(this) {
        loaded ?: (app?.let { c -> runCatching { parse(c.assets.open("catalog/providers.json").bufferedReader().readText()) }.getOrNull() } ?: emptyList())
            .also { if (app != null) loaded = it }
    }

    fun parse(json: String): List<CatalogProvider> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ms = o.optJSONArray("models") ?: JSONArray()
            CatalogProvider(o.getString("id"), o.optString("name"), o.optString("kind", "openai"), o.optString("api"), o.optString("env"),
                (0 until ms.length()).mapNotNull { j ->
                    val m = ms.optJSONArray(j) ?: return@mapNotNull null
                    runCatching {
                        ModelInfo(m.getString(0), m.optString(1, m.getString(0)), m.optInt(2), m.optInt(3) == 1,
                            m.optDouble(4, 0.0), m.optDouble(5, 0.0), m.optDouble(6, 0.0), m.optDouble(7, 0.0), m.optInt(8))
                    }.getOrNull()
                })
        }
    }

    private val modelIndex by lazy { providers.flatMap { p -> p.models.map { it.id to it } }.groupBy({ it.first }, { it.second }) }

    /** Best match for a model id: exact id, then without an "org/" prefix, then the catalog provider whose API matches. */
    fun model(modelId: String, baseUrl: String? = null): ModelInfo? {
        baseUrl?.let { u -> providers.firstOrNull { it.api.trimEnd('/') == u.trimEnd('/') }?.models?.firstOrNull { it.id == modelId }?.let { return it } }
        return modelIndex[modelId]?.firstOrNull() ?: modelIndex[modelId.substringAfterLast('/')]?.firstOrNull()
    }

    fun forUrl(baseUrl: String): CatalogProvider? = providers.firstOrNull { it.api.trimEnd('/') == baseUrl.trimEnd('/') }
}
