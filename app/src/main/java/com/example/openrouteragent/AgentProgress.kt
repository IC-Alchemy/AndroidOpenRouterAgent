package com.example.openrouteragent

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_LIVE_TEXT = 96_000
private const val MAX_SUMMARY = 12_000

internal data class LiveTool(val id: String, val name: String, val status: String, val detail: String?)
internal data class LiveFile(val containerId: String, val file: ContainerFileRef)

internal sealed interface RunUpdate {
    data object Pulse : RunUpdate
    data class Phase(val phase: String, val detail: String?, val step: Int?) : RunUpdate
    data class Text(val text: String, val summary: Boolean) : RunUpdate
    data class Tool(val entry: LiveTool) : RunUpdate
    data class Usage(val value: TokenUsage) : RunUpdate
    data class Source(val value: WebCitation) : RunUpdate
    data class Files(val value: List<LiveFile>) : RunUpdate
}

internal data class AgentProgress(
    val started: Long,
    val lastEvent: Long = started,
    val phase: String = "starting",
    val detail: String? = null,
    val step: Int = 1,
    val model: String? = null,
    val answer: String = "",
    val summary: String = "",
    val tools: List<LiveTool> = emptyList(),
    val sources: List<WebCitation> = emptyList(),
    val files: List<LiveFile> = emptyList(),
    val usage: TokenUsage? = null,
    val revision: Int = 0,
) {
    fun apply(updates: List<RunUpdate>, now: Long): AgentProgress {
        if (updates.isEmpty()) return this
        var result = this
        for (update in updates) {
            result = when (update) {
                RunUpdate.Pulse -> result
                is RunUpdate.Phase -> result.copy(phase = update.phase, detail = update.detail,
                    step = update.step ?: result.step,
                    model = if (update.phase == "waiting") update.detail ?: result.model else result.model)
                is RunUpdate.Text -> if (update.summary) {
                    result.copy(summary = (result.summary + update.text).takeLast(MAX_SUMMARY), phase = "reasoning")
                } else {
                    result.copy(answer = (result.answer + update.text).takeLast(MAX_LIVE_TEXT), phase = "answering")
                }
                is RunUpdate.Tool -> {
                    val next = update.entry
                    val index = result.tools.indexOfFirst { it.id == next.id }
                    val tools = if (index < 0) (result.tools + next).takeLast(48)
                        else result.tools.toMutableList().apply { set(index, next) }
                    result.copy(tools = tools)
                }
                is RunUpdate.Usage -> result.copy(usage = update.value)
                is RunUpdate.Source -> result.copy(sources = (result.sources + update.value).distinctBy { it.url }.take(32))
                is RunUpdate.Files -> result.copy(files = (result.files + update.value)
                    .distinctBy { it.containerId to it.file.fileId }.takeLast(48))
            }
        }
        return result.copy(lastEvent = now, revision = revision + 1)
    }
}

internal fun parseRunUpdates(events: JSONArray): List<RunUpdate> = buildList {
    for (index in 0 until events.length().coerceAtMost(80)) {
        val event = events.optJSONObject(index) ?: continue
        when (event.optString("kind")) {
            "pulse" -> add(RunUpdate.Pulse)
            "phase" -> add(RunUpdate.Phase(event.optString("phase"), event.string("detail"),
                event.optInt("step").takeIf { it > 0 }))
            "text", "reasoning" -> add(RunUpdate.Text(event.optString("text"), event.optString("kind") == "reasoning"))
            "tool" -> add(RunUpdate.Tool(LiveTool(event.getString("id"), event.getString("name"),
                event.getString("status"), event.string("detail"))))
            "usage" -> {
                val input = event.optInt("inputTokens")
                val output = event.optInt("outputTokens")
                add(RunUpdate.Usage(TokenUsage(input, output, input + output,
                    event.optDouble("cost").takeIf { it.isFinite() }, event.optInt("cachedTokens"))))
            }
            "source" -> {
                val url = event.string("url")?.takeIf { it.toHttpUrlOrNull() != null } ?: continue
                add(RunUpdate.Source(WebCitation(event.string("title") ?: url, url)))
            }
            "files" -> {
                val container = event.string("containerId") ?: continue
                val files = event.optJSONArray("files") ?: continue
                val parsed = (0 until files.length().coerceAtMost(48)).mapNotNull {
                    val file = files.optJSONObject(it) ?: return@mapNotNull null
                    val id = file.string("id") ?: return@mapNotNull null
                    val name = file.string("name") ?: return@mapNotNull null
                    LiveFile(container, ContainerFileRef(id, name))
                }
                add(RunUpdate.Files(parsed))
            }
        }
    }
}

private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
