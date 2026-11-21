package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject

data class CatalogModel(
    val id: String,
    val name: String,
    val contextLength: Int,
    val parameters: Set<String>
) {
    val supportsTools: Boolean get() = "tools" in parameters
}

internal fun parseModelCatalog(raw: String): List<CatalogModel> {
    val data = JSONObject(raw).getJSONArray("data")
    return (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim().ifBlank { return@mapNotNull null }
        val parameters = item.optJSONArray("supported_parameters") ?: JSONArray()
        CatalogModel(id, item.optString("name").ifBlank { id }, item.optInt("context_length"),
            (0 until parameters.length()).mapNotNull { parameters.opt(it) as? String }.toSet())
    }.distinctBy { it.id }
}

internal fun selectModel(config: AgentConfig, models: List<CatalogModel>): CatalogModel? {
    val eligible = if (config.agentToolsEnabled()) models.filter { it.supportsTools } else models
    return eligible.firstOrNull { it.id == config.model }
        ?: eligible.firstOrNull { it.id == DEFAULT_MODEL }
        ?: eligible.firstOrNull()
}
