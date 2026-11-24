package com.example.openrouteragent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val ORIGIN = "https://agent-runtime.invalid"
private const val ENTRY_URL = "$ORIGIN/index.html"
private const val MAX_HISTORY_BYTES = 6_000_000
private const val RUN_TIMEOUT_MILLIS = 15 * 60_000L

data class ToolApproval(
    val decision: String, val name: String, val arguments: String, val url: String, val asksUser: Boolean = false,
    val savesSettings: Boolean = false,
)
data class SdkRunResult(val result: AgentRunResult, val history: String?, val state: String? = null)

internal open class AgentSdkRuntime(private val context: Context) {
    private data class ActiveRun(
        val id: String,
        val completion: CompletableDeferred<SdkRunResult>,
        val apiKey: String,
        val model: String,
        val idleSeconds: Long,
        val onUpdate: (List<RunUpdate>) -> Unit,
        val onApproval: (ToolApproval) -> Unit,
        val actions: CoroutineScope,
        val onAppAction: (suspend (String, JSONObject) -> JSONObject)?,
        val miniApps: MiniAppTools?,
        val localScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val main = Handler(Looper.getMainLooper())
    private val transport = AgentHttpTransport()
    private var host: FrameLayout? = null
    private var view: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private var activeRun: ActiveRun? = null

    // Request interception runs on WebView worker threads.
    @Volatile private var approvedToolOrigins: Set<Triple<String, String, Int>> = emptySet()
    // The run and chat whose media the runtime may read and save. Read on the JavaScript bridge thread.
    @Volatile private var mediaRun: Pair<String, String>? = null
    private val media = chatMediaStore(context)

    open suspend fun run(
        settings: AgentConfig,
        workspace: Workspace,
        prompt: String,
        model: CatalogModel,
        catalog: List<CatalogModel>,
        guide: SavedAgentSkill?,
        onUpdate: (List<RunUpdate>) -> Unit,
        onApproval: (ToolApproval) -> Unit,
        files: OpenRouterRunFiles = OpenRouterRunFiles(),
        onAppAction: (suspend (String, JSONObject) -> JSONObject)? = null,
    ): SdkRunResult = withContext(Dispatchers.Main.immediate) {
        check(activeRun == null)
        approvedToolOrigins = if (settings.sdk.toolChoice == "none") emptySet() else
            (listOf(settings.mcpUrl) + settings.customTools.filter { it.enabled && it.handler == "webhook" }
                .map { it.value }).mapNotNull { it.toHttpUrlOrNull() }
                .map { Triple(it.scheme, it.host, it.port) }.toSet()
        val run = ActiveRun(UUID.randomUUID().toString(), CompletableDeferred(), settings.apiKey, model.id,
            settings.sdk.streamIdleSeconds?.toLong() ?: DEFAULT_STREAM_IDLE_SECONDS, onUpdate, onApproval,
            CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job])), onAppAction,
            miniApps = if (settings.miniApps && settings.sdk.toolChoice != "none") MiniAppTools(
                repository = miniAppRepository(context),
                libraries = MiniAppLibraries(File(context.applicationContext.cacheDir, "miniapp-libraries")),
                renderIcon = { svg, color, name -> MiniAppIconRenderer.renderSvgToPng(svg, color, name) },
            ) else null)
        try {
            val runtime = view ?: createWebView()
            try {
                withTimeout(15_000) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("The Agent SDK did not start. Update Android System WebView and retry.", e)
            }
            activeRun = run
            mediaRun = run.id to workspace.id
            val request = withContext(Dispatchers.Default) {
                runtimeOptions(settings, workspace, prompt, model, catalog, guide, files, onAppAction != null).put("id", run.id)
            }
            runtime.evaluateJavascript(
                "typeof globalThis.AgentRuntime?.start === 'function' && " +
                    "(globalThis.AgentRuntime.start($request), true)"
            ) { accepted ->
                if (accepted != "true") run.completion.completeExceptionally(
                    IOException("Android could not start the Agent SDK request."))
            }
            try {
                withTimeout(RUN_TIMEOUT_MILLIS) { run.completion.await() }
            } catch (e: TimeoutCancellationException) {
                runCatching { android.util.Log.w("AgentHttp", "run-timeout id=${run.id} model=${run.model}") }
                throw IOException("The agent run timed out after 15 minutes. Long hosted tool work can " +
                    "exceed this limit; split the task into smaller steps.", e)
            }
        } finally {
            if (!run.completion.isCompleted) view?.let { dispatch(it, "stop", run.id) }
            run.actions.cancel()
            transport.cancelRun(run.id)
            run.localScope.cancel()
            approvedToolOrigins = emptySet()
            if (mediaRun?.first == run.id) mediaRun = null
            if (activeRun?.id == run.id) activeRun = null
            if (!ready.isCompleted || ready.isCancelled) destroy()
        }
    }

    fun attach(container: FrameLayout) {
        if (host === container) return
        host = container
        view?.let { bindView(it, container) }
    }

    fun detach(container: FrameLayout) {
        if (host !== container) return
        view?.let(container::removeView)
        host = null
    }

    private fun bindView(runtime: WebView, container: FrameLayout) {
        (runtime.parent as? ViewGroup)?.removeView(runtime)
        container.addView(runtime, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun decide(decision: String, accept: Boolean, answer: String) {
        val run = activeRun ?: return
        view?.let { dispatch(it, "decide", run.id, decision, accept, answer) }
    }

    fun destroy() {
        activeRun?.let { transport.cancelRun(it.id); it.completion.cancel() }
        view?.let {
            it.removeJavascriptInterface("AndroidAgent")
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        view = null
        ready = CompletableDeferred()
    }

    @SuppressLint("SetJavaScriptEnabled") // The only document and script are packaged read-only assets.
    private fun createWebView(): WebView {
        val runtime = WebView(context)
        ready = CompletableDeferred()
        view = runtime
        runtime.isFocusable = false
        runtime.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        runtime.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        host?.let { bindView(runtime, it) }
        runtime.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
        }
        runtime.addJavascriptInterface(EventBridge(runtime), "AndroidAgent")
        runtime.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.isForMainFrame) return if (url == ENTRY_URL) asset("index.html", "text/html") else denied()
                if (url == "$ORIGIN/agent.js") return asset("agent.js", "application/javascript")
                val target = url.toHttpUrlOrNull() ?: return denied()
                return if (target.isHttps && Triple(target.scheme, target.host, target.port) in approvedToolOrigins) {
                    null
                } else denied()
            }

            override fun onPageStarted(webView: WebView, url: String, favicon: Bitmap?) {
                if (url != ENTRY_URL) webView.stopLoading()
            }

            override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    val error = IOException("Android restarted the Agent SDK runtime. Try the run again.")
                    activeRun?.completion?.completeExceptionally(error)
                    ready.completeExceptionally(error)
                    view = null
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                return true
            }

            override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) ready.completeExceptionally(IOException("Android could not load the Agent SDK."))
            }
        }
        runtime.loadUrl(ENTRY_URL)
        return runtime
    }

    private fun asset(name: String, type: String): WebResourceResponse =
        WebResourceResponse(type, "UTF-8", context.assets.open("agent/$name"))

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun dispatch(runtime: WebView, method: String, vararg args: Any) {
        val values = args.joinToString(",") { if (it is String) JSONObject.quote(it) else it.toString() }
        runtime.evaluateJavascript("globalThis.AgentRuntime?.$method($values); void 0", null)
    }

    private inner class EventBridge(private val runtime: WebView) {
        @JavascriptInterface
        fun requestHttp(runId: String, requestId: String, json: String) {
            val payload = if (json.length <= MAX_HISTORY_BYTES * 2) {
                runCatching { JSONObject(json) }.getOrNull()
            } else null
            val body = payload?.opt("body") as? String
            val route = when (payload?.optString("endpoint")) {
                "" -> AgentEndpoint.Responses
                "chatCompletions" -> AgentEndpoint.ChatCompletions
                else -> null
            }
            main.post {
                val run = activeRun ?: return@post
                if (runtime !== view || run.id != runId || run.completion.isCompleted) return@post
                if (body == null || route == null) {
                    dispatch(runtime, "http", runId, requestId, "error", "The OpenRouter request is too large or invalid.")
                    return@post
                }
                val model = if (route == AgentEndpoint.Responses) run.model else
                    runCatching { JSONObject(body).optString("model").takeIf(String::isNotBlank) }.getOrNull()
                transport.start(runId, requestId, run.apiKey, body, model, run.idleSeconds, route) { kind, value ->
                    main.post {
                        if (runtime === view && activeRun?.id == runId) {
                            dispatch(runtime, "http", runId, requestId, kind, value)
                        }
                    }
                }
            }
        }

        /** Local tools that run in Kotlin, such as mini apps. Replies arrive through AgentRuntime.local. */
        @JavascriptInterface
        fun local(runId: String, requestId: String, json: String) {
            main.post {
                val run = activeRun ?: return@post
                if (runtime !== view || run.id != runId || run.completion.isCompleted) return@post
                run.localScope.launch {
                    val result = try {
                        val request = JSONObject(json)
                        when (request.optString("tool")) {
                            // Saving can download libraries, so the tools run off the main thread.
                            "mini_app" -> run.miniApps?.let { tools -> withContext(Dispatchers.IO) { tools.handle(request) } }
                                ?: JSONObject().put("ok", false).put("error", "Mini apps are off in Settings.")
                            else -> JSONObject().put("ok", false).put("error", "Unknown local tool.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", "The tool failed: ${e.message ?: e}")
                    }
                    main.post {
                        if (runtime === view && activeRun?.id == runId) {
                            dispatch(runtime, "local", runId, requestId, result.toString())
                        }
                    }
                }
            }
        }

        /** Returns a stored attachment or image as a data URL ("url") or bare base64 ("b64"), or "" when it is gone. */
        @JavascriptInterface
        fun readMedia(runId: String, kind: String, file: String): String {
            val (id, chatId) = mediaRun ?: return ""
            if (id != runId || (kind != "url" && kind != "b64")) return ""
            return runCatching { media.encoded(chatId, file, kind == "url") }.getOrDefault("")
        }

        /** Saves a photo, document or recording from the run, returning its file name, or "" when it is not one. */
        @JavascriptInterface
        fun storeMedia(runId: String, kind: String, value: String): String {
            val (id, chatId) = mediaRun ?: return ""
            if (id != runId || (kind != "url" && kind != "b64") || value.length > MAX_HISTORY_BYTES * 2) return ""
            return runCatching { media.storeEncoded(chatId, value, kind == "url") }.getOrNull().orEmpty()
        }

        @JavascriptInterface
        fun readHttp(runId: String, requestId: String) = transport.read(runId, requestId)

        @JavascriptInterface
        fun cancelHttp(runId: String, requestId: String) = transport.cancel(runId, requestId)

        @JavascriptInterface
        fun post(json: String) {
            try {
                if (json.length > MAX_HISTORY_BYTES * 2) throw IOException("The agent response is too large.")
                val event = JSONObject(json)
                val id = event.optString("id")
                val handler: (ActiveRun) -> Unit = when (event.optString("type")) {
                    "ready" -> {
                        main.post { if (runtime === view) ready.complete(Unit) }
                        return
                    }
                    "updates" -> {
                        val updates = parseRunUpdates(event.getJSONArray("value"))
                        ({ run: ActiveRun -> run.onUpdate(updates) })
                    }
                    "approval" -> {
                        val value = event.getJSONObject("value")
                        val approval = ToolApproval(value.getString("decision"), value.getString("name"),
                            value.getJSONObject("args").toString(2), value.optString("url"), value.optBoolean("asksUser"),
                            value.optBoolean("savesSettings"))
                        ({ run: ActiveRun -> run.onApproval(approval) })
                    }
                    "app_request" -> {
                        val value = event.getJSONObject("value")
                        val request = value.getString("request")
                        val action = value.getString("name")
                        val args = value.getJSONObject("args")
                        ({ run: ActiveRun ->
                            run.actions.launch {
                                try {
                                    val callback = run.onAppAction ?: throw IOException("App tools are unavailable.")
                                    val result = callback(action, args)
                                    if (activeRun === run && runtime === view) {
                                        dispatch(runtime, "appResult", run.id, request, true, result)
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (activeRun === run && runtime === view) {
                                        dispatch(runtime, "appResult", run.id, request, false, error.userMessage(run.apiKey))
                                    }
                                }
                            }
                            Unit
                        })
                    }
                    "done" -> {
                        val value = event.getJSONObject("value")
                        val history = value.optJSONArray("history")?.toString()?.takeIf { it.length <= MAX_HISTORY_BYTES }
                        val state = (value.opt("state") as? String)?.takeIf { it.length <= MAX_HISTORY_BYTES }
                        val result = SdkRunResult(parseAgentResult(value.getJSONObject("response").toString()), history, state)
                        ({ run: ActiveRun -> run.completion.complete(result); Unit })
                    }
                    "error" -> {
                        val error = IOException(sdkErrorText(event.getString("value")))
                        ({ run: ActiveRun -> run.completion.completeExceptionally(error); Unit })
                    }
                    else -> return
                }
                main.post {
                    val run = activeRun ?: return@post
                    if (runtime === view && run.id == id && !run.completion.isCompleted) handler(run)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                main.post {
                    if (runtime === view) activeRun?.completion?.completeExceptionally(
                        if (e is IOException) e else IOException("Android received an invalid Agent SDK event.", e))
                }
            }
        }
    }
}

/** The SDK wraps transport failures as "Unexpected HTTP client error: Error: <text>". Keep only the text. */
internal fun sdkErrorText(message: String): String =
    message.replace(Regex("^Unexpected HTTP client error:\\s*(Error:\\s*)?"), "")

internal fun runtimeOptions(
    config: AgentConfig, workspace: Workspace, prompt: String, model: CatalogModel,
    catalog: List<CatalogModel> = listOf(model), guide: SavedAgentSkill? = null,
    files: OpenRouterRunFiles = OpenRouterRunFiles(), appActions: Boolean = false,
): JSONObject {
    val transcript = JSONArray()
    workspace.messages.filter { !it.isError && !it.isModerated && it.text.isNotBlank() }.forEach {
        transcript.put(JSONObject().put("type", "message").put("role", if (it.isUser) "user" else "assistant")
            .put("content", it.text))
    }
    val history = if (workspace.sdkState != null) JSONArray()
        else workspace.sdkHistory?.let { runCatching { JSONArray(it) }.getOrNull() } ?: transcript
    val chosen = (listOf(model.id, config.sdk.followUpModel.trim()) +
        config.sdk.fallbackModels.split(',').map(String::trim)).filter(String::isNotBlank).toSet()
    val modelDetails = JSONObject()
    catalog.filter { it.id in chosen }.forEach {
        modelDetails.put(it.id, JSONObject().put("tools", it.supportsTools).put("parameters", JSONArray(it.parameters))
            .put("input", JSONArray(it.inputModalities)).put("output", JSONArray(it.outputModalities)))
    }
    val effectiveTools = config.customTools.filter { it.enabled && config.sdk.toolChoice != "none" }
    val skills = (listOfNotNull(guide) + config.savedSkills).filter { it.enabled }
    return JSONObject().put("apiKey", config.apiKey).put("model", model.id).put("history", history)
        .put("prompt", prompt).put("instructions", config.systemInstructions)
        .put("sessionId", workspace.sessionId ?: workspace.id).put("state", workspace.sdkState)
        .put("shell", config.enableShellSandbox).put("containerId", files.projectSandbox ?: workspace.containerId)
        .put("domains", JSONArray(config.sandboxDomains())).put("webSearch", config.enableWebSearch)
        .put("dateTime", config.enableDateTime).put("webFetch", config.webFetch).put("deviceTools", config.deviceTools)
        .put("workspaceFiles", config.enableWorkspaceFiles).put("projectFolder", files.projectFolder)
        .put("appActions", appActions).put("github", appActions && files.github)
        .put("bodyBuilderModels", JSONArray(if (appActions && model.supportsTools) catalog.map { it.id } else emptyList()))
        .put("fileIds", JSONArray(files.attachments.map { it.id }))
        .put("attachmentNames", JSONArray(files.attachments.map { it.id.takeLast(8) + "-" + it.filename.substringAfterLast('/') }))
        .put("attachments", JSONArray(files.media.map { (item, text) ->
            JSONObject().put("kind", item.kind).put("file", item.file).put("name", item.name)
                .put("mimeType", item.mimeType).put("text", text)
        }))
        .put("miniApps", config.miniApps)
        .put("extraTools", JSONArray(config.extraToolsJson.ifBlank { "[]" }))
        .put("mcpUrl", config.mcpUrl).put("mcpToken", config.mcpToken)
        .put("maxSteps", config.maxSteps).put("maxCost", config.maxCost)
        .put("temperature", config.temperature.takeIf { "temperature" in model.parameters })
        .put("baseTemperature", config.temperature)
        .put("sdk", JSONObject(Json.encodeToString(SdkSettings.serializer(), config.sdk)))
        .put("modelDetails", modelDetails)
        .put("customTools", JSONArray(effectiveTools.map {
            JSONObject(Json.encodeToString(CustomAgentTool.serializer(), it))
        }))
        .put("skills", JSONArray(skills.map { JSONObject(Json.encodeToString(SavedAgentSkill.serializer(), it)) }))
}
