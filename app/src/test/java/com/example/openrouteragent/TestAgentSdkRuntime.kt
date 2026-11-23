package com.example.openrouteragent

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec

internal fun testAgentStorage(app: Application) = AgentStorage(app) {
    SecretKeySpec(ByteArray(32), "AES")
}

/** Supplies model output at the SDK boundary. The JavaScript suite exercises the real SDK transport. */
internal class TestAgentSdkRuntime(app: Application, private val server: MockWebServer) : AgentSdkRuntime(app) {
    private val http = OkHttpClient()

    override suspend fun run(
        settings: AgentConfig,
        workspace: Workspace,
        prompt: String,
        model: CatalogModel,
        catalog: List<CatalogModel>,
        guide: SavedAgentSkill?,
        onUpdate: (List<RunUpdate>) -> Unit,
        onApproval: (ToolApproval) -> Unit,
        files: OpenRouterRunFiles,
        onAppAction: (suspend (String, JSONObject) -> JSONObject)?,
    ): SdkRunResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(server.url("/v1/responses"))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(prompt.toRequestBody())
            .build()
        http.newCall(request).execute().use {
            check(it.isSuccessful)
            val result = AgentRunResult(
                assistantText = checkNotNull(it.body).string(),
                reasoningSummary = null,
                shellSteps = emptyList(),
                searchQueries = emptyList(),
                citations = emptyList(),
                discoveredContainerId = null,
                usage = null,
                modelUsed = model.id,
            )
            SdkRunResult(result, history = null)
        }
    }
}
