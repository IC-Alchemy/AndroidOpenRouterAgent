package com.example.openrouteragent

private val options = setOf(RegexOption.IGNORE_CASE)
private val openRouter = Regex("""open\s*-?\s*router""", options)
private val video = Regex("""\b(videos?|video\s*clips?|mp4s?|movies?|footage)\b""", options)
private val downloadFromOpenRouter = Regex(
    """\bdownload\w*\b[^.;\n]{0,80}?\bfrom\s+(the\s+)?open\s*-?\s*router|open\s*-?\s*router\b[^.;\n]{0,40}?\bdownload""",
    options)
private val openRouterMakesVideo = Regex(
    """(open\s*-?\s*router|\bllm\b|\bmodel\b)[^.;\n]{0,60}?\b(generat\w*|creat\w*|render\w*|make|making|produc\w*)\b""" +
        """[^.;\n]{0,40}?\b(videos?|mp4s?|clips?|movies?|footage|them)\b""",
    options)

/** Services that really download or generate video, and a direct URL to fetch. */
private val videoSource = Regex(
    """\b(runway\w*|luma|pika|stability|stable\s+video|replicate|fal(\.ai)?|kling|veo|sora|minimax|hailuo|""" +
        """heygen|synthesia|yt-dlp|youtube-dl)\b|https?://""",
    options)

/** Text an LLM can produce about a video. */
private val llmVideoArtifact = Regex(
    """\bvideo\s+(scripts?|prompts?|ideas?|titles?|descriptions?|captions?|storyboards?|outlines?|transcripts?|""" +
        """summar\w*)\b""",
    options)

/**
 * Rejects requests that only make sense if OpenRouter itself downloads or generates video. OpenRouter routes
 * LLM requests; the app's catalog only lists text models. Runs before any network call.
 */
internal fun commandProblem(prompt: String): String? {
    if (!openRouter.containsMatchIn(prompt) || !video.containsMatchIn(prompt)) return null
    if (videoSource.containsMatchIn(prompt)) return null
    val downloads = downloadFromOpenRouter.containsMatchIn(prompt)
    val generates = openRouterMakesVideo.containsMatchIn(prompt) && !llmVideoArtifact.containsMatchIn(prompt)
    if (!downloads && !generates) return null
    return "OpenRouter does not provide video download/generation. Use a video provider. " +
        "This request was not sent. OpenRouter routes LLM requests for text, code and prompts. To continue, " +
        "ask for the LLM part only (for example, \"write five video prompts and save them to outputs/prompts.json\"), " +
        "name a video generation service such as Runway, Luma, Pika, Stability, Replicate or fal.ai, " +
        "or give a direct video URL to download with the sandbox network allowlist."
}
