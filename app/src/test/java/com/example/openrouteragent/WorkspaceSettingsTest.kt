package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSettingsTest {
    private val gpt = CatalogModel("openai/gpt-4o", "GPT-4o", 128000, setOf("tools"),
        canonicalSlug = "openai/gpt-4o-2024-05-13")
    private val claude = CatalogModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", 200000, setOf("tools"))
    private val llama = CatalogModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", 131000, setOf("tools"))

    @Test
    fun onlyOpenAiShowsOnlyModelsOpenAiHosts() {
        val rules = GuardrailRules().onlyProvider("OpenAI")
        assertEquals(listOf("openai"), rules.allowedProviders)
        val providers = listOf(ProviderInfo("Anthropic", "anthropic"), ProviderInfo("OpenAI", "openai"))
        assertEquals(listOf("OpenAI"), shownProviderNames(rules, providers))
        val hosted = setOf("openai/gpt-4o")
        assertEquals(listOf(gpt), filterModelsForWorkspace(listOf(gpt, claude, llama), rules, hosted))
        // No rules leaves the catalog as OpenRouter returned it.
        assertEquals(3, filterModelsForWorkspace(listOf(gpt, claude, llama), null, null).size)
    }

    @Test
    fun providerTogglesKeepTheOtherProvidersAsTheyAre() {
        val hidden = GuardrailRules().withProvider("DeepInfra", false)
        assertEquals(listOf("deepinfra"), hidden.ignoredProviders)
        assertNull(hidden.allowedProviders)
        assertFalse(hidden.showsProvider("deepinfra"))
        assertTrue(hidden.showsProvider("together"))
        // Showing it again drops the empty ignore list, which OpenRouter would reject.
        val shown = hidden.withProvider("deepinfra", true)
        assertNull(shown.ignoredProviders)
        assertFalse(shown.hasProviderRules)

        val allow = GuardrailRules(allowedProviders = listOf("openai")).withProvider("anthropic", true)
        assertEquals(listOf("openai", "anthropic"), allow.allowedProviders)
        val narrowed = allow.withProvider("openai", false)
        assertEquals(listOf("anthropic"), narrowed.allowedProviders)
        assertEquals("Keep at least one provider shown.", narrowed.withProvider("anthropic", false).problem())
        assertFalse(GuardrailRules(allowedProviders = listOf("openai")).allProviders().hasProviderRules)
    }

    @Test
    fun modelTogglesMatchCanonicalSlugs() {
        val rules = GuardrailRules(ignoredModels = listOf("openai/gpt-4o-2024-05-13"))
        assertFalse(rules.showsModel(gpt))
        assertTrue(rules.showsModel(claude))
        assertNull(rules.withModel(gpt, true).ignoredModels)

        val only = GuardrailRules().onlyModel(claude)
        assertEquals(listOf(claude), filterModelsForWorkspace(listOf(gpt, claude), only, null))
        val two = only.withModel(gpt, true)
        assertEquals(listOf("anthropic/claude-sonnet-4", "openai/gpt-4o"), two.allowedModels)
        assertEquals(listOf("openai/gpt-4o"), two.withModel(claude, false).allowedModels)
        assertEquals(listOf("anthropic/claude-sonnet-4"), GuardrailRules().withModel(claude, false).ignoredModels)
    }

    @Test
    fun patchBodySendsEveryListWithNullForNoRestriction() {
        val body = GuardrailRules(allowedProviders = listOf("openai"), ignoredModels = listOf("openai/o1")).patchBody()
        assertEquals("""["openai"]""", body.getJSONArray("allowed_providers").toString())
        assertTrue(body.isNull("ignored_providers"))
        assertTrue(body.isNull("allowed_models"))
        assertEquals("""["openai/o1"]""", body.getJSONArray("ignored_models").toString())
        assertEquals(setOf("allowed_providers", "ignored_providers", "allowed_models", "ignored_models"),
            body.keySet())
    }

    @Test
    fun parsesKeysWorkspacesGuardrailsProvidersAndModelIds() {
        val key = parseKeyInfo("""{"data":{"label":"Phone","workspace_id":"ws-1","is_management_key":false}}""")
        assertEquals(KeyInfo("Phone", "ws-1", false), key)
        assertNull(parseKeyInfo("""{"data":{"label":"x"}}""").workspaceId)

        val (workspaces, total) = parseWorkspaces("""{"data":[
            {"id":"ws-1","name":"Default","slug":"default","default_guardrail_id":"g-1"},
            {"id":"ws-2","slug":"team","default_guardrail_id":null},
            {"name":"no id"}],"total_count":7}""")
        assertEquals(7, total)
        assertEquals(listOf("Default", "team"), workspaces.map { it.name })
        assertEquals("g-1", workspaces[0].defaultGuardrailId)
        assertNull(workspaces[1].defaultGuardrailId)

        val rules = parseGuardrailRules("""{"data":{"id":"g-1","allowed_providers":["OpenAI"],
            "ignored_providers":null,"allowed_models":[],"ignored_models":["x/y"]}}""")
        assertEquals(GuardrailRules(allowedProviders = listOf("openai"), ignoredModels = listOf("x/y")), rules)

        val providers = parseProviders("""{"data":[{"name":"Together","slug":"together"},
            {"name":"Anthropic","slug":"anthropic"},{"name":"dup","slug":"Anthropic"}]}""")
        assertEquals(listOf("Anthropic", "Together"), providers.map { it.name })

        assertEquals(setOf("openai/gpt-4o", "openai/gpt-4o-2024-05-13"), parseModelIds(
            """{"data":[{"id":"openai/gpt-4o","canonical_slug":"openai/gpt-4o-2024-05-13"}]}"""))
    }

    private fun endpoint(name: String, tag: String, prompt: Double, completion: Double, throughput: Double? = null,
                         latency: Double? = null, tier: String? = null) =
        ModelEndpoint(name, tag, prompt / 1_000_000, completion / 1_000_000, throughput = throughput,
            latency = latency, serviceTier = tier)

    private val endpoints = listOf(
        endpoint("DeepInfra", "deepinfra/fp8", 0.23, 0.40, throughput = 40.0, latency = 900.0),
        endpoint("Together", "together", 0.88, 0.88, throughput = 120.0, latency = 400.0),
        endpoint("Groq", "groq", 0.59, 0.79, throughput = 300.0, latency = 250.0),
        endpoint("Cheap Flex", "flexco", 0.10, 0.10, throughput = 10.0, tier = "flex"),
    )

    @Test
    fun routedPriceFollowsTheRoutingMode() {
        val floor = routedPrice(endpoints, SdkSettings(routingVariant = "floor"), null)!!
        assertEquals("Cheap Flex", floor.provider)
        val price = routedPrice(endpoints, SdkSettings(providerSort = "price"), null)!!
        assertEquals("DeepInfra", price.provider)
        assertEquals("Groq", routedPrice(endpoints, SdkSettings(routingVariant = "nitro"), null)!!.provider)
        assertEquals("Groq", routedPrice(endpoints, SdkSettings(providerSort = "latency"), null)!!.provider)

        val auto = routedPrice(endpoints, SdkSettings(), null)!!
        assertNull(auto.provider)
        assertEquals(3, auto.providerCount)
        assertEquals("3 providers · $0.23–$0.88 in · $0.4–$0.88 out per 1M tokens", routedPriceLabel(auto))
        assertEquals("DeepInfra · $0.23 in · $0.4 out per 1M tokens", routedPriceLabel(price))
    }

    @Test
    fun routedPriceRespectsWorkspaceAndRequestProviderSettings() {
        val rules = GuardrailRules(allowedProviders = listOf("together", "groq"))
        assertEquals("Groq", routedPrice(endpoints, SdkSettings(providerSort = "price"), rules)!!.provider)
        val ordered = routedPrice(endpoints, SdkSettings(providerOrder = "together, groq"), null)!!
        assertEquals("Provider order", ordered.mode)
        assertEquals("Together", ordered.provider)
        assertEquals("Together", routedPrice(endpoints, SdkSettings(providerOnly = "Together"), null)!!.provider)
        assertEquals("Together", routedPrice(endpoints,
            SdkSettings(providerSort = "price", providerIgnore = "deepinfra,groq"), null)!!.provider)
        assertEquals("Groq", routedPrice(endpoints,
            SdkSettings(providerSort = "throughput", maxPromptPrice = 0.6), null)!!.provider)
        assertNull(routedPrice(endpoints, SdkSettings(), GuardrailRules(allowedProviders = listOf("openai"))))
    }

    @Test
    fun parsesEndpointPricesWithDiscountsAndStripsRoutingSuffixes() {
        val parsed = parseModelEndpoints("""{"data":{"id":"meta-llama/llama-3.3-70b-instruct","endpoints":[
            {"provider_name":"DeepInfra","tag":"deepinfra/fp8","quantization":"fp8",
             "pricing":{"prompt":"0.0000002","completion":"0.0000004","request":"0","discount":0.5},
             "throughput_last_30m":{"p50":41.5},"latency_last_30m":800},
            {"provider_name":"Broken","tag":"broken","pricing":{"prompt":"n/a","completion":"1"}}]}}""")
        assertEquals(1, parsed.size)
        val deepInfra = parsed.single()
        assertEquals("deepinfra", deepInfra.providerSlug)
        assertEquals(0.0000001, deepInfra.prompt, 1e-12)
        assertEquals(41.5, deepInfra.throughput!!, 0.0)
        assertEquals(800.0, deepInfra.latency!!, 0.0)
        assertTrue(deepInfra.matches("DeepInfra"))

        assertEquals("openai/gpt-4o", withoutRoutingVariant("openai/gpt-4o:nitro"))
        assertEquals("meta-llama/llama-3.3-70b-instruct:free",
            withoutRoutingVariant("meta-llama/llama-3.3-70b-instruct:free"))
    }

    @Test
    fun clientCallsTheWorkspaceGuardrailAndEndpointApis() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"ws-1","name":"Default","default_guardrail_id":"g-1"}],
            "total_count":1}"""))
        assertEquals("ws-1", client.listWorkspaces("mgmt-key").single().id)
        var request = server.takeRequest()
        assertEquals("/api/v1/workspaces?offset=0&limit=100", request.path)
        assertEquals("Bearer mgmt-key", request.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody("""{"data":{"allowed_providers":["openai"]}}"""))
        val saved = client.updateGuardrailRules("mgmt-key", "g-1", GuardrailRules(allowedProviders = listOf("openai")))
        assertEquals(listOf("openai"), saved.allowedProviders)
        request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/guardrails/g-1", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("openai", body.getJSONArray("allowed_providers").getString(0))
        assertTrue(body.isNull("allowed_models"))

        server.enqueue(MockResponse().setBody("""{"data":[{"id":"openai/gpt-4o"}]}"""))
        assertEquals(setOf("openai/gpt-4o"), client.modelIdsHostedBy("key", listOf("OpenAI", "Azure")))
        assertEquals("/api/v1/models?providers=OpenAI%2CAzure", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"data":{"endpoints":[]}}"""))
        client.modelEndpoints("key", "openai/gpt-4o:floor")
        assertEquals("/api/v1/models/openai/gpt-4o/endpoints", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"data":{"label":"k","workspace_id":"ws-2"}}"""))
        assertEquals("ws-2", client.keyInfo("key").workspaceId)
        assertEquals("/api/v1/key", server.takeRequest().path)
    }

    private fun withServer(block: suspend (MockWebServer, OpenRouterAgentClient) -> Unit) {
        MockWebServer().use { server ->
            runBlocking { block(server, OpenRouterAgentClient(server.url("/api/v1/"))) }
        }
    }
}
