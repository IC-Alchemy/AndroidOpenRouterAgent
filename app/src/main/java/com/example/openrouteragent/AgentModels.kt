package com.example.openrouteragent

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Persisted user configuration for the OpenRouter Managed Agent.
 */
@Serializable
data class AgentConfig(
    val apiKey: String = "",
    val sdk: SdkSettings = SdkSettings(),
    val customTools: List<CustomAgentTool> = emptyList(),
    val savedSkills: List<SavedAgentSkill> = emptyList(),
    val model: String = DEFAULT_MODEL,
    val enableShellSandbox: Boolean = true,
    val enableWebSearch: Boolean = true,
    val enableDateTime: Boolean = true,
    val webFetch: Boolean = false,
    val enableWorkspaceFiles: Boolean = false,
    /** Adds the openrouter:image_generation server tool, so the agent can draw pictures in chat. */
    val imageGeneration: Boolean = false,
    /** The image model for that tool. Empty uses OpenRouter's default. */
    val imageModel: String = "",
    /** An aspect ratio such as 16:9. Empty lets the model choose. */
    val imageAspect: String = "",
    /** low, medium or high. Empty lets the model choose. */
    val imageQuality: String = "",
    val deviceTools: Boolean = false,
    val miniApps: Boolean = true,
    val enableModeration: Boolean = false,
    val mcpUrl: String = "",
    val mcpToken: String = "",
    val maxSteps: Int = 12,
    val maxCost: Double? = null,
    val temperature: Double? = null,
    val extraToolsJson: String = "",
    val allowSandboxNetwork: Boolean = false,
    val allowedDomainsCsv: String = "pypi.org, files.pythonhosted.org, registry.npmjs.org, github.com",
    val systemInstructions: String = "Help the user with their task. Use enabled tools when useful. Save requested deliverables under ~/outputs and tell the user their filenames."
)

/**
 * Represents a single command result executed inside the OpenRouter Linux container.
 */
@Serializable
data class ShellCommandResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val outcome: String
)

/**
 * Represents a file created or modified inside the OpenRouter container.
 */
@Serializable
data class ContainerFileRef(
    val fileId: String,
    val path: String,
    val bytes: Long? = null
)

/**
 * Represents a single execution block of `openrouter:shell` returned by the Responses API.
 */
@Serializable
data class ShellExecutionStep(
    val containerId: String?,
    val commands: List<String>,
    val results: List<ShellCommandResult>,
    val files: List<ContainerFileRef>
)

/**
 * Represents a web citation returned by `openrouter:web_search`.
 */
@Serializable
data class WebCitation(
    val title: String,
    val url: String
)

/**
 * Token usage stats reported by the Responses API.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cost: Double? = null,
    val cachedTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

/**
 * A single message in the agent conversation feed.
 */
@Serializable
data class ChatEntry(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val reasoningSummary: String? = null,
    val shellSteps: List<ShellExecutionStep> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val citations: List<WebCitation> = emptyList(),
    val usage: TokenUsage? = null,
    val modelUsed: String? = null,
    val isError: Boolean = false,
    val isModerated: Boolean = false,
    val notice: String? = null,
    val toolActivity: List<ToolActivity> = emptyList(),
    /** Photos and files the user attached, or images the model made. */
    val media: List<ChatMedia> = emptyList(),
)

@Serializable
data class ToolActivity(val name: String, val status: String, val detail: String?)

/**
 * Parsed result from a single POST `/api/v1/responses` agent call.
 */
data class AgentRunResult(
    val assistantText: String,
    val reasoningSummary: String?,
    val shellSteps: List<ShellExecutionStep>,
    val searchQueries: List<String>,
    val citations: List<WebCitation>,
    val discoveredContainerId: String?,
    val usage: TokenUsage?,
    val modelUsed: String?,
    val notice: String? = null,
    /** Images the model made, already saved with the chat. */
    val media: List<ChatMedia> = emptyList(),
)

const val DEFAULT_MODEL = "anthropic/claude-sonnet-4.5"

/** Aspect ratios offered for generated images. The empty value lets the model choose. */
val IMAGE_ASPECTS = listOf("", "1:1", "16:9", "9:16", "4:3", "3:4")
val IMAGE_QUALITIES = listOf("", "low", "medium", "high")

fun AgentConfig.sandboxDomains(): List<String> {
    if (!enableShellSandbox || !allowSandboxNetwork) return emptyList()
    return allowedDomainsCsv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .distinct().sorted()
}

private val hostname = Regex("""(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?""")

fun AgentConfig.settingsError(): String? = when {
    apiKey.trim().any { it.code !in 33..126 } ->
        "Enter an API key without spaces."
    model.trim().any { it.isWhitespace() } ->
        "Enter a model ID without spaces."
    imageModel.trim().any { it.isWhitespace() } -> "Enter an image model ID without spaces."
    imageAspect !in IMAGE_ASPECTS -> "Choose an image aspect ratio from the list."
    imageQuality !in IMAGE_QUALITIES -> "Choose an image quality from the list."
    enableShellSandbox && allowSandboxNetwork &&
        (sandboxDomains().isEmpty() || sandboxDomains().any { !hostname.matches(it) }) ->
        "Enter one or more domain names, separated by commas."
    maxSteps !in 1..30 -> "Enter a step limit between 1 and 30."
    maxCost != null && (!maxCost.isFinite() || maxCost <= 0) -> "Enter a positive cost threshold."
    temperature != null && (!temperature.isFinite() || temperature !in 0.0..2.0) ->
        "Enter a temperature between 0 and 2."
    mcpUrl.isNotBlank() && mcpUrl.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() &&
        it.password.isEmpty() && it.fragment == null } != true -> "Enter an HTTPS MCP endpoint without URL credentials."
    mcpToken.trim().any { it.code !in 33..126 } -> "Enter an MCP token without spaces."
    mcpToken.isNotBlank() && mcpToken.trim() == apiKey.trim() -> "Use a separate token for the MCP server."
    customTools.size > 32 || customTools.map { it.name }.distinct().size != customTools.size ||
        customTools.any { it.problem() != null } -> "Check your custom tools for invalid or duplicate names."
    savedSkills.size > 32 || savedSkills.map { it.name }.distinct().size != savedSkills.size ||
        savedSkills.any { it.name == GUIDE_NAME || it.problem() != null } -> "Check your saved skills for invalid or duplicate names."
    else -> hostedToolsError(extraToolsJson) ?: sdk.problem()
}

@Serializable
data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val messages: List<ChatEntry> = emptyList(),
    val containerId: String? = null,
    val accountTag: String = "",
    val sandboxDomains: List<String> = emptyList(),
    val sdkHistory: String? = null,
    val sessionId: String? = null,
    val sdkState: String? = null,
    val interruptedTurn: InterruptedTurn? = null,
    /** Chat mode sends messages without tools: cheaper, faster, and fine for models without tool calling. */
    val chatMode: Boolean = false,
)

/** The settings for a message sent in chat mode, or retried without tools: every tool and tool source is off. */
fun AgentConfig.withoutTools(): AgentConfig = copy(enableShellSandbox = false, enableWebSearch = false, imageGeneration = false,
    enableDateTime = false, webFetch = false, deviceTools = false, enableWorkspaceFiles = false,
    miniApps = false, mcpUrl = "", mcpToken = "", extraToolsJson = "", customTools = emptyList(),
    sdk = sdk.copy(discoverSkills = false, toolChoice = "none"))

@Serializable
data class InterruptedTurn(
    val prompt: String, val reply: String, val reasoningSummary: String, val error: String?,
    val media: List<ChatMedia> = emptyList(),
)

fun AgentConfig.agentToolsEnabled(): Boolean =
    sdk.toolChoice != "none" && (enableShellSandbox || enableWebSearch || enableDateTime || webFetch || deviceTools ||
        imageGeneration ||
        enableWorkspaceFiles || customTools.any { it.enabled } || mcpUrl.isNotBlank() ||
        (extraToolsJson.isNotBlank() && extraToolsJson.replace(Regex("\\s"), "") != "[]"))
