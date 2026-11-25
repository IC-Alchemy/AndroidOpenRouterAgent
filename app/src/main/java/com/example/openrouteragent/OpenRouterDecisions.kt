package com.example.openrouteragent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/*
 * OpenRouter Decisions (alpha): POST https://openrouter.ai/api/alpha/decisions.
 * A decision model answers typed questions about `state` with one answer and its probabilities:
 * `noul` (yes or no), `choice` (one label), `score` (a level) and `box` (a region of an image).
 * Media goes in the top-level `state` array as image_url, input_audio and video_url parts.
 * Which media and question types an endpoint accepts depends on the model; one it can't serve fails
 * with 400 before anything is sent upstream. The API is alpha, so its shape may change.
 */

internal const val DECISIONS_URL = "https://openrouter.ai/api/alpha/decisions"
internal const val DECISION_MODELS_URL = "https://openrouter.ai/api/v1/models?output_modalities=decisions"

/** OpenRouter's example decision model. It reads text and PNG or JPEG images. */
internal const val DEFAULT_DECISION_MODEL = "typesafe/jev-1.13"

/** The agent tool name. Users can't save a custom tool with this name. */
internal const val DECISION_TOOL_NAME = "openrouter_decide"

/** The question key the app uses in every request it builds. */
private const val QUESTION_KEY = "decision"

/** Image, video and audio limits that every decision model shares, from OpenRouter's multimodal guide. */
internal const val MAX_DECISION_MEDIA_BYTES = 16L * 1024 * 1024
internal const val MAX_DECISION_REQUEST_BYTES = 30L * 1024 * 1024
internal const val MAX_DECISION_OPTIONS = 4

internal const val DECISION_TEXT = "text"
internal const val DECISION_IMAGE = "image"
internal const val DECISION_VIDEO = "video"
internal const val DECISION_AUDIO = "audio"

/** Audio formats a decision model takes in `input_audio.format`. */
private val audioFormats = mapOf(
    "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
    "audio/mp4" to "m4a", "audio/m4a" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/flac" to "flac",
    "audio/x-flac" to "flac", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/webm" to "webm",
    "audio/aiff" to "aiff", "audio/x-aiff" to "aiff",
)

internal fun decisionAudioFormat(mimeType: String): String? = audioFormats[mimeType.lowercase().substringBefore(';').trim()]

/**
 * One piece of what is being decided on: some text, or an image, video or audio clip as base64.
 * Images are PNG or JPEG, which every image-reading decision model accepts.
 */
internal data class DecisionPart(
    val kind: String,
    val text: String = "",
    val mimeType: String = "",
    val base64: String = "",
    val name: String = "",
) {
    val isMedia: Boolean get() = kind != DECISION_TEXT
    val isEmpty: Boolean get() = if (kind == DECISION_TEXT) text.isBlank() else base64.isEmpty()

    /** The state entry for this part: a plain string for text, a content part for media. */
    fun stateEntry(): Any = when (kind) {
        DECISION_TEXT -> text.trim()
        DECISION_IMAGE -> JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:$mimeType;base64,$base64"))
        DECISION_VIDEO -> JSONObject().put("type", "video_url")
            .put("video_url", JSONObject().put("url", "data:$mimeType;base64,$base64"))
        DECISION_AUDIO -> JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
            .put("data", base64).put("format", decisionAudioFormat(mimeType) ?: "wav"))
        else -> throw IllegalArgumentException("Unknown decision input.")
    }

    companion object {
        fun text(value: String) = DecisionPart(DECISION_TEXT, text = value)
    }
}

/** What kind of answer to ask for. */
internal enum class DecisionKind(val apiType: String, val label: String) {
    Choice("choice", "Pick one"),
    YesNo("noul", "Yes or no"),
    Score("score", "Score"),
}

/** One candidate in a pick-one decision. [label] is the name the model answers with, such as "A". */
internal data class DecisionOption(val label: String, val parts: List<DecisionPart>)

/**
 * A decision to send. [options] are the candidates of a [DecisionKind.Choice]. [subject] is what a yes-or-no or score
 * decision judges; in a pick-one decision it is shared context shown before the options.
 */
internal data class DecisionAsk(
    val model: String,
    val question: String,
    val kind: DecisionKind,
    val options: List<DecisionOption> = emptyList(),
    val subject: List<DecisionPart> = emptyList(),
    val levels: List<String> = emptyList(),
    val context: String = "",
) {
    val parts: List<DecisionPart> get() = options.flatMap { it.parts } + subject

    /** Why the decision can't be sent yet, or null when it can. */
    fun problem(): String? = when {
        model.isBlank() -> "Choose a decision model."
        question.isBlank() -> "Type the question to decide."
        kind == DecisionKind.Choice && options.count { option -> option.parts.any { !it.isEmpty } } < 2 ->
            "Fill in at least two options."
        kind == DecisionKind.Choice && options.size > MAX_DECISION_OPTIONS -> "Use up to $MAX_DECISION_OPTIONS options."
        kind == DecisionKind.Choice && options.map { it.label }.distinct().size != options.size -> "Give each option its own name."
        kind != DecisionKind.Choice && subject.none { !it.isEmpty } -> "Add the text, photo, video or audio to judge."
        kind == DecisionKind.Score && levels.count { it.isNotBlank() } < 2 -> "List at least two score levels."
        parts.any { it.isMedia && it.base64.length * 3L / 4 > MAX_DECISION_MEDIA_BYTES } ->
            "Each photo, video or audio clip must be 16 MB or smaller."
        parts.sumOf { it.base64.length.toLong() + it.text.length } > MAX_DECISION_REQUEST_BYTES ->
            "The inputs are too large to send together. Remove a video or audio clip."
        else -> null
    }
}

/**
 * The request body. Text and media go in one top-level `state` array, because decision models read media only
 * there. Each pick-one option is introduced by "Option <label>:" so the model can tell which input is which.
 */
internal fun decisionRequestBody(ask: DecisionAsk): JSONObject {
    val state = JSONArray()
    if (ask.context.isNotBlank()) state.put(ask.context.trim())
    val question = JSONObject().put("type", ask.kind.apiType).put("instructions", ask.question.trim())
    when (ask.kind) {
        DecisionKind.Choice -> {
            ask.subject.filterNot { it.isEmpty }.forEach { state.put(it.stateEntry()) }
            val criteria = JSONObject()
            ask.options.filter { option -> option.parts.any { !it.isEmpty } }.forEach { option ->
                state.put("Option ${option.label}:")
                option.parts.filterNot { it.isEmpty }.forEach { state.put(it.stateEntry()) }
                val text = option.parts.filter { it.kind == DECISION_TEXT && !it.isEmpty }.joinToString(" ") { it.text.trim() }
                val media = option.parts.filter { it.isMedia && !it.isEmpty }.map { it.kind }.distinct()
                criteria.put(option.label, when {
                    media.isEmpty() -> text.take(2_000)
                    text.isBlank() -> "The ${media.joinToString(" and ")} shown after \"Option ${option.label}:\"."
                    else -> "${text.take(1_900)} (with the ${media.joinToString(" and ")} after \"Option ${option.label}:\")"
                })
            }
            question.put("criteria", criteria)
        }
        DecisionKind.YesNo -> ask.subject.filterNot { it.isEmpty }.forEach { state.put(it.stateEntry()) }
        DecisionKind.Score -> {
            ask.subject.filterNot { it.isEmpty }.forEach { state.put(it.stateEntry()) }
            question.put("criteria", JSONArray(ask.levels.map(String::trim).filter(String::isNotEmpty)))
        }
    }
    return JSONObject().put("model", ask.model.trim()).put("state", state)
        .put("questions", JSONObject().put(QUESTION_KEY, question))
}

/** One typed answer. A model may refuse a question; then [refused] is true and the values are empty. */
internal data class DecisionAnswer(
    val type: String,
    val choice: String? = null,
    /** The yes-or-no value, from 0 (no) to 1 (yes). */
    val noul: Double? = null,
    val score: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val legend: Map<String, String> = emptyMap(),
    val confidence: Double? = null,
    val box: DecisionBox? = null,
) {
    val refused: Boolean get() = type == "refusal"

    /** The yes-or-no reading of [noul]. */
    val yes: Boolean? get() = noul?.let { it >= 0.5 }

    /** The level the score is closest to, with its legend text. */
    val scoreLevel: Pair<Int, String>? get() {
        val value = score ?: return null
        val levels = legend.keys.mapNotNull(String::toIntOrNull).sorted()
        val nearest = levels.minByOrNull { kotlin.math.abs(it - value) } ?: return null
        return nearest to legend[nearest.toString()].orEmpty()
    }
}

internal data class DecisionBox(val top: Double, val bottom: Double, val left: Double, val right: Double)

internal data class DecisionResult(
    val answers: Map<String, DecisionAnswer>,
    val model: String = "",
    val provider: String = "",
    val id: String = "",
    val cost: Double? = null,
) {
    /** The answer to the app's own question, or the only answer. */
    val answer: DecisionAnswer? get() = answers[QUESTION_KEY] ?: answers.values.singleOrNull()
}

internal fun parseDecisionResponse(raw: String): DecisionResult {
    val body = JSONObject(raw)
    val answers = body.optJSONObject("answers") ?: throw IOException("OpenRouter returned no decision.")
    return DecisionResult(
        answers = answers.keys().asSequence().mapNotNull { key ->
            answers.optJSONObject(key)?.let { key to parseDecisionAnswer(it) }
        }.toMap(),
        model = body.optString("model"),
        provider = body.optString("provider"),
        id = body.optString("id"),
        cost = body.optJSONObject("usage")?.optDouble("cost")?.takeIf { it.isFinite() },
    )
}

private fun parseDecisionAnswer(value: JSONObject): DecisionAnswer {
    fun number(key: String) = value.opt(key).let { (it as? Number)?.toDouble() ?: (it as? String)?.toDoubleOrNull() }
    val probabilities = value.optJSONObject("probabilities")?.let { map ->
        map.keys().asSequence().mapNotNull { key -> (map.opt(key) as? Number)?.toDouble()?.let { key to it } }.toMap()
    }.orEmpty()
    val legend = value.optJSONObject("legend")?.let { map ->
        map.keys().asSequence().associateWith { map.optString(it) }
    }.orEmpty()
    val box = value.optJSONObject("box")?.let {
        DecisionBox(it.optDouble("top"), it.optDouble("bottom"), it.optDouble("left"), it.optDouble("right"))
    }
    return DecisionAnswer(
        type = value.optString("type").ifBlank { "unknown" },
        choice = (value.opt("choice") as? String)?.takeIf { it.isNotBlank() },
        noul = number("noul"),
        score = number("score"),
        probabilities = probabilities,
        legend = legend,
        confidence = number("confidence"),
        box = box,
    )
}

/** The media kinds in [parts] that [model] doesn't read, judged from the catalog. Unknown models pass. */
internal fun unsupportedDecisionMedia(model: CatalogModel?, parts: List<DecisionPart>): List<String> =
    if (model == null || !model.modalitiesKnown) emptyList()
    else parts.filter { it.isMedia && !it.isEmpty }.map { it.kind }.distinct().filterNot { it in model.inputModalities }

/** A plain sentence for a model that can't read some inputs, naming models that can when the catalog has any. */
internal fun unsupportedMediaMessage(model: String, kinds: List<String>, catalog: List<CatalogModel>): String {
    val names = kinds.joinToString(" or ") { if (it == DECISION_IMAGE) "photos" else it }
    val able = catalog.filter { candidate -> kinds.all { it in candidate.inputModalities } }.take(3).map { it.name }
    return "$model can't read $names." + if (able.isEmpty()) "" else " Try ${able.joinToString(", ")}."
}

/**
 * The message for a failed decision. A 400 usually means the model can't serve this input or question type, so the
 * message says that, plus OpenRouter's own reason.
 */
internal fun decisionErrorMessage(code: Int, raw: String, apiKey: String): String {
    val body = runCatching { JSONObject(raw) }.getOrNull()
    val detail = (body?.optJSONObject("error")?.optString("message") ?: body?.optString("message"))
        ?.takeIf { it.isNotBlank() }
    val message = when (code) {
        400 -> "This model can't take this decision as asked. Some models read only text and photos, and only a few " +
            "read video or audio; pick another model or remove the input it can't read." +
            (detail?.let { " OpenRouter said: $it" } ?: "")
        401 -> "Check your API key in Settings."
        402 -> "Check your OpenRouter credit balance."
        403 -> "This key can't use decision models." + (detail?.let { " $it" } ?: "")
        404 -> "OpenRouter doesn't know this decision model." + (detail?.let { " $it" } ?: "")
        413 -> "The photo, video or audio is too large for this model. Use a smaller or shorter one."
        422 -> "The model's provider couldn't read an input." + (detail?.let { " $it" } ?: "")
        429 -> "Rate limit reached. Wait a moment before retrying."
        else -> detail ?: "OpenRouter couldn't take the decision."
    }
    val key = apiKey.trim()
    return "OpenRouter HTTP $code. " + (if (key.isEmpty()) message else message.replace(key, "[API key]")).take(600)
}

/** Calls OpenRouter's Decisions endpoint and lists decision models. */
internal class OpenRouterDecisionsClient(
    private val decisionsUrl: HttpUrl = DECISIONS_URL.toHttpUrl(),
    private val modelsUrl: HttpUrl = DECISION_MODELS_URL.toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build(),
) {
    suspend fun decide(apiKey: String, ask: DecisionAsk): DecisionResult {
        ask.problem()?.let { throw IllegalArgumentException(it) }
        return decide(apiKey, decisionRequestBody(ask))
    }

    suspend fun decide(apiKey: String, body: JSONObject): DecisionResult {
        val request = request(apiKey, decisionsUrl)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request, apiKey) { parseDecisionResponse(it.body?.string().orEmpty()) }
    }

    /** Models that answer decisions, newest catalog first. */
    suspend fun models(apiKey: String): List<CatalogModel> =
        execute(request(apiKey, modelsUrl).build(), apiKey) { parseModelCatalog(it.body?.string().orEmpty()) }

    private fun request(apiKey: String, url: HttpUrl): Request.Builder {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key in Settings." }
        return Request.Builder().url(url).header("Authorization", "Bearer ${apiKey.trim()}").openRouterAttribution()
    }

    private suspend fun <T> execute(request: Request, apiKey: String, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        continuation.resumeWith(runCatching {
                            if (!it.isSuccessful) {
                                throw ApiStatusException(it.code,
                                    decisionErrorMessage(it.code, it.peekBody(8192).string(), apiKey))
                            }
                            read(it)
                        })
                    }
                }
            })
        }
}

// The agent tool. It runs on the phone: the runtime sends the call to Android, which asks OpenRouter and replies.

/** The custom tool spec the runtime registers for the agent. The handler is the runtime's "ask" kind, answered by Android. */
internal fun decisionToolSpec(): JSONObject = JSONObject()
    .put("id", "builtin-$DECISION_TOOL_NAME")
    .put("name", DECISION_TOOL_NAME)
    .put("description", "Ask an OpenRouter decision model for one typed decision, with probabilities. Use it to " +
        "classify, pick the best of up to four options, answer a yes-or-no check, or score something on a scale, " +
        "including photos, audio or video the user attached to this message (set attached to true). kind is " +
        "\"choice\" (give options), \"yes_no\", or \"score\" (give levels, lowest first). Each call is billed.")
    .put("schema", JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("question", JSONObject().put("type", "string").put("maxLength", 2000)
                .put("description", "The question, e.g. Which headline is clearer?"))
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("choice", "yes_no", "score")))
                .put("description", "choice, yes_no or score."))
            .put("options", JSONObject().put("type", "array").put("maxItems", MAX_DECISION_OPTIONS)
                .put("items", JSONObject().put("type", "string").put("maxLength", 4000))
                .put("description", "For choice: the candidates as text. With attached media, each attachment is an " +
                    "option in order and these are their descriptions."))
            .put("levels", JSONObject().put("type", "array").put("maxItems", 10)
                .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                .put("description", "For score: the levels, lowest first."))
            .put("content", JSONObject().put("type", "string").put("maxLength", 16000)
                .put("description", "Text to judge, or context for the decision."))
            .put("attached", JSONObject().put("type", "boolean")
                .put("description", "Include the photos, audio and video attached to the user's current message."))
            .put("model", JSONObject().put("type", "string").put("maxLength", 200)
                .put("description", "A decision model ID. Leave out to use the one in Settings.")))
        .put("required", JSONArray(listOf("question", "kind")))
        .toString())
    .put("handler", "ask")
    .put("value", "")
    .put("enabled", true)

/**
 * Turns the agent's tool arguments into a decision. With [attached] media and kind choice, each attachment becomes
 * an option in order (A, B, ...), described by the matching text option if there is one; otherwise text options are
 * the candidates and attachments are shared context.
 */
internal fun decisionAskFromTool(args: JSONObject, defaultModel: String, attached: List<DecisionPart>): DecisionAsk {
    val question = args.optString("question").trim()
    val kind = when (args.optString("kind").trim().lowercase()) {
        "choice" -> DecisionKind.Choice
        "yes_no", "yesno", "noul", "yes-no" -> DecisionKind.YesNo
        "score" -> DecisionKind.Score
        else -> throw IllegalArgumentException("kind must be choice, yes_no or score.")
    }
    fun strings(key: String) = args.optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
    }.orEmpty()
    val texts = strings("options")
    val media = if (args.optBoolean("attached")) attached.filter { it.isMedia && !it.isEmpty } else emptyList()
    val content = args.optString("content").trim()
    val model = args.optString("model").trim().ifBlank { defaultModel.trim().ifBlank { DEFAULT_DECISION_MODEL } }
    val labels = ('A'..'Z').map(Char::toString)
    return when (kind) {
        DecisionKind.Choice -> if (media.size >= 2) DecisionAsk(model, question, kind,
            options = media.take(MAX_DECISION_OPTIONS).mapIndexed { index, part ->
                DecisionOption(labels[index], listOfNotNull(texts.getOrNull(index)?.let(DecisionPart::text), part))
            }, context = content)
        // Fewer than two attachments: the text options are the candidates and any attachment is shared context.
        else DecisionAsk(model, question, kind,
            options = texts.take(MAX_DECISION_OPTIONS).mapIndexed { index, text ->
                DecisionOption(labels[index], listOf(DecisionPart.text(text)))
            }, subject = media, context = content)
        DecisionKind.YesNo, DecisionKind.Score -> DecisionAsk(model, question, kind,
            subject = listOfNotNull(content.takeIf(String::isNotEmpty)?.let(DecisionPart::text)) + media,
            levels = strings("levels"))
    }
}

/** The JSON the agent gets back: the answer, its probabilities, the options' labels, and the cost. */
internal fun decisionToolReply(ask: DecisionAsk, result: DecisionResult): String {
    val answer = result.answer
    val reply = JSONObject().put("model", result.model.ifBlank { ask.model })
    if (result.cost != null) reply.put("cost_usd", result.cost)
    when {
        answer == null -> reply.put("error", "The model returned no answer.")
        answer.refused -> reply.put("refused", true)
        else -> {
            when (ask.kind) {
                DecisionKind.Choice -> {
                    reply.put("choice", answer.choice)
                    val text = ask.options.firstOrNull { it.label == answer.choice }?.parts
                        ?.firstOrNull { it.kind == DECISION_TEXT }?.text
                    if (text != null) reply.put("choice_text", text.take(500))
                }
                DecisionKind.YesNo -> reply.put("answer", if (answer.yes == true) "yes" else "no").put("yes_value", answer.noul)
                DecisionKind.Score -> {
                    reply.put("score", answer.score)
                    answer.scoreLevel?.let { (_, text) -> reply.put("level", text) }
                }
            }
            if (answer.probabilities.isNotEmpty()) reply.put("probabilities", JSONObject(answer.probabilities))
            answer.confidence?.let { reply.put("confidence", it) }
        }
    }
    return reply.toString()
}

internal fun decisionToolError(message: String): String = JSONObject().put("error", message.take(600)).toString()

/** Whether a run offers the agent the openrouter_decide tool. */
internal fun decisionToolEnabled(config: AgentConfig): Boolean = config.decisionsTool && config.sdk.toolChoice != "none"
