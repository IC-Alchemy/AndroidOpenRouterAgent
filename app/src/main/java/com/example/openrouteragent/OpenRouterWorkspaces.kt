package com.example.openrouteragent

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter workspaces, their provider and model rules, and per-provider pricing.
 *
 * Workspaces and guardrails are read and written through OpenRouter's management API, which needs a
 * management key. The provider and model rules live on the workspace's default guardrail, so OpenRouter
 * stores and enforces them; the app keeps only which workspace is selected.
 */

/** What OpenRouter says about an API key: `GET /api/v1/key`. */
data class KeyInfo(val label: String, val workspaceId: String?, val isManagementKey: Boolean)

/** One workspace from `GET /api/v1/workspaces`. */
data class OpenRouterWorkspace(
    val id: String,
    val name: String,
    val slug: String,
    val defaultGuardrailId: String?,
    val description: String? = null,
)

/** A provider from `GET /api/v1/providers`: a display name such as OpenAI and its ID such as openai. */
data class ProviderInfo(val name: String, val slug: String)

/**
 * The provider and model lists of a guardrail. Null means no restriction. Provider IDs are lowercase slugs.
 * Model entries are model slugs or canonical slugs, which OpenRouter accepts interchangeably.
 */
data class GuardrailRules(
    val allowedProviders: List<String>? = null,
    val ignoredProviders: List<String>? = null,
    val allowedModels: List<String>? = null,
    val ignoredModels: List<String>? = null,
) {
    val hasProviderRules: Boolean get() = allowedProviders != null || ignoredProviders != null
    val hasModelRules: Boolean get() = allowedModels != null || ignoredModels != null

    fun showsProvider(slug: String): Boolean {
        val id = slug.trim().lowercase()
        return (allowedProviders == null || id in allowedProviders) && id !in ignoredProviders.orEmpty()
    }

    fun showsModel(model: CatalogModel): Boolean {
        val ids = model.identifiers()
        return (allowedModels == null || allowedModels.any { it in ids }) &&
            ignoredModels.orEmpty().none { it in ids }
    }

    /** Shows or hides one provider, keeping the rest as they are. */
    fun withProvider(slug: String, shown: Boolean): GuardrailRules {
        val id = slug.trim().lowercase()
        val allowed = allowedProviders
        val next = when {
            shown -> copy(allowedProviders = allowed?.plus(id)?.distinct(), ignoredProviders = ignoredProviders?.minus(id))
            allowed != null -> copy(allowedProviders = allowed.minus(id))
            else -> copy(ignoredProviders = ignoredProviders.orEmpty().plus(id).distinct())
        }
        return next.normalized()
    }

    fun onlyProvider(slug: String) = copy(allowedProviders = listOf(slug.trim().lowercase()), ignoredProviders = null)
    fun allProviders() = copy(allowedProviders = null, ignoredProviders = null)

    /** Shows or hides one model. [model] is the model's slug, as in the catalog's `id`. */
    fun withModel(model: CatalogModel, shown: Boolean): GuardrailRules {
        val ids = model.identifiers()
        val allowed = allowedModels
        val next = when {
            shown -> copy(allowedModels = allowed?.plus(model.id)?.distinct(), ignoredModels = ignoredModels?.filterNot { it in ids })
            allowed != null -> copy(allowedModels = allowed.filterNot { it in ids })
            else -> copy(ignoredModels = ignoredModels.orEmpty().filterNot { it in ids }.plus(model.id))
        }
        return next.normalized()
    }

    fun onlyModel(model: CatalogModel) = copy(allowedModels = listOf(model.id), ignoredModels = null)
    fun allModels() = copy(allowedModels = null, ignoredModels = null)

    /**
     * OpenRouter rejects an empty list, so an empty ignore list becomes null (nothing ignored). An empty
     * allow list would hide everything; callers keep at least one entry, see [problem].
     */
    private fun normalized() = copy(ignoredProviders = ignoredProviders?.takeIf { it.isNotEmpty() },
        ignoredModels = ignoredModels?.takeIf { it.isNotEmpty() })

    fun problem(): String? = when {
        allowedProviders?.isEmpty() == true -> "Keep at least one provider shown."
        allowedModels?.isEmpty() == true -> "Keep at least one model shown."
        else -> null
    }

    /** The body of `PATCH /api/v1/guardrails/{id}`: every list, with null clearing a restriction. */
    fun patchBody(): JSONObject = JSONObject().apply {
        put("allowed_providers", allowedProviders.toJson())
        put("ignored_providers", ignoredProviders.toJson())
        put("allowed_models", allowedModels.toJson())
        put("ignored_models", ignoredModels.toJson())
    }
}

private fun List<String>?.toJson(): Any = if (isNullOrEmpty()) JSONObject.NULL else JSONArray(this)

private fun CatalogModel.identifiers(): Set<String> = setOfNotNull(id, canonicalSlug)

/** The catalog as the selected workspace shows it: its model rules, and only models its shown providers host. */
internal fun filterModelsForWorkspace(
    models: List<CatalogModel>, rules: GuardrailRules?, providerModelIds: Set<String>?,
): List<CatalogModel> {
    if (rules == null) return models
    return models.filter { model ->
        rules.showsModel(model) &&
            (providerModelIds == null || model.id in providerModelIds || model.canonicalSlug in providerModelIds)
    }
}

/** Display names of the providers a workspace shows, for the catalog's `providers` filter. */
internal fun shownProviderNames(rules: GuardrailRules, providers: List<ProviderInfo>): List<String> =
    providers.filter { rules.showsProvider(it.slug) }.map { it.name }

internal fun parseKeyInfo(raw: String): KeyInfo {
    val data = JSONObject(raw).optJSONObject("data") ?: JSONObject()
    return KeyInfo(data.optString("label"), data.text("workspace_id"), data.optBoolean("is_management_key"))
}

internal fun parseWorkspaces(raw: String): Pair<List<OpenRouterWorkspace>, Int> {
    val root = JSONObject(raw)
    val data = root.optJSONArray("data") ?: JSONArray()
    val list = (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val id = item.text("id") ?: return@mapNotNull null
        OpenRouterWorkspace(id, item.text("name") ?: item.text("slug") ?: id, item.text("slug").orEmpty(),
            item.text("default_guardrail_id"), item.text("description"))
    }
    return list to root.optInt("total_count", list.size)
}

internal fun parseGuardrailRules(raw: String): GuardrailRules {
    val root = JSONObject(raw)
    val data = root.optJSONObject("data") ?: root
    return GuardrailRules(
        allowedProviders = data.list("allowed_providers")?.map(String::lowercase),
        ignoredProviders = data.list("ignored_providers")?.map(String::lowercase),
        allowedModels = data.list("allowed_models"),
        ignoredModels = data.list("ignored_models"),
    )
}

internal fun parseProviders(raw: String): List<ProviderInfo> {
    val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
    return (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val slug = item.text("slug")?.lowercase() ?: return@mapNotNull null
        ProviderInfo(item.text("name") ?: slug, slug)
    }.distinctBy { it.slug }.sortedBy { it.name.lowercase() }
}

internal fun parseModelIds(raw: String): Set<String> {
    val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
    return (0 until data.length()).flatMap { index ->
        val item = data.optJSONObject(index) ?: return@flatMap emptyList()
        listOfNotNull(item.text("id"), item.text("canonical_slug"))
    }.toSet()
}

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONObject.list(key: String): List<String>? {
    val array = optJSONArray(key) ?: return null
    return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
        .distinct().takeIf { it.isNotEmpty() }
}
