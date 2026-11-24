package com.example.openrouteragent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Reads agent replies aloud with the phone's text-to-speech engine. One reply plays at a time;
 * [speakingId] is the reply being read, so its button can show Stop.
 */
internal class ReplySpeaker(context: Context) {
    var speakingId by mutableStateOf<String?>(null)
        private set
    var unavailable by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var waiting: Pair<String, String>? = null
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        onReady(status == TextToSpeech.SUCCESS)
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
    }

    private fun onReady(ok: Boolean) {
        ready = ok
        unavailable = !ok
        if (ok) {
            val locale = Locale.getDefault()
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        }
        val next = waiting
        waiting = null
        if (ok && next != null) speak(next.first, next.second) else if (!ok) speakingId = null
    }

    /** The engine reports on its own thread. Only the last piece of a reply marks it finished. */
    private fun finished(utteranceId: String?) {
        val id = utteranceId ?: return
        if (!id.endsWith(LAST)) return
        val reply = id.substringBefore('#')
        main.post { if (speakingId == reply) speakingId = null }
    }

    /** Reads [markdown] aloud, replacing anything already playing. */
    fun speak(id: String, markdown: String) {
        speakingId = id
        if (!ready) {
            waiting = id to markdown
            return
        }
        val chunks = speechChunks(speakableText(markdown), maxOf(200, TextToSpeech.getMaxSpeechInputLength() - 100))
        if (chunks.isEmpty()) {
            speakingId = null
            return
        }
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val tag = id + "#" + index + if (index == chunks.lastIndex) LAST else ""
            if (engine.speak(chunk, mode, null, tag) == TextToSpeech.ERROR) {
                speakingId = null
                return
            }
        }
    }

    fun stop() {
        waiting = null
        speakingId = null
        if (ready) engine.stop()
    }

    fun toggle(id: String, markdown: String) = if (speakingId == id) stop() else speak(id, markdown)

    fun shutdown() {
        waiting = null
        engine.stop()
        engine.shutdown()
    }

    private companion object {
        const val LAST = "#last"
    }
}

/** A speaker tied to the screen; it stops talking when the screen goes away. */
@Composable
internal fun rememberReplySpeaker(): ReplySpeaker {
    val context = LocalContext.current
    val speaker = remember { ReplySpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

private val fencedCode = Regex("```[\\s\\S]*?(```|$)")
private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val bareUrl = Regex("https?://\\S+")
private val heading = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+")
private val quote = Regex("(?m)^[ \\t]{0,3}>[ \\t]?")
private val bullet = Regex("(?m)^[ \\t]*(?:[-*+]|\\d+[.)])[ \\t]+")
private val rule = Regex("(?m)^[ \\t]*(?:[-*_][ \\t]*){3,}$")
private val tableDivider = Regex("(?m)^[ \\t]*\\|?[ \\t]*:?-{3,}:?[ \\t]*(\\|[ \\t]*:?-{3,}:?[ \\t]*)*\\|?[ \\t]*$")
private val emphasis = Regex("(?<!\\w)(\\*\\*|__|\\*|_|~~|`)(?=\\S)([^\\n]*?\\S)\\1(?!\\w)")
private val blankLines = Regex("\\n{3,}")

/** Turns a Markdown reply into plain sentences, so the speech engine doesn't read out symbols and links. */
internal fun speakableText(markdown: String): String {
    var text = markdown.replace("\r\n", "\n")
    text = fencedCode.replace(text) { "\n(Code omitted.)\n" }
    text = image.replace(text) { it.groupValues[1].ifBlank { "Image" } + "." }
    text = link.replace(text) { it.groupValues[1] }
    text = bareUrl.replace(text, "link")
    text = rule.replace(text, "")
    text = tableDivider.replace(text, "")
    text = heading.replace(text, "")
    text = quote.replace(text, "")
    text = bullet.replace(text, "")
    repeat(2) { text = emphasis.replace(text) { it.groupValues[2] } }
    text = text.lines().joinToString("\n") { line ->
        if (line.trim().startsWith("|")) line.trim().trim('|').split('|').joinToString(", ") { it.trim() } else line
    }
    return blankLines.replace(text, "\n\n").trim()
}

/** Splits text at paragraph or sentence ends into pieces the speech engine accepts. */
internal fun speechChunks(text: String, max: Int): List<String> {
    val out = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > max) {
        val window = rest.substring(0, max)
        val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'),
            window.lastIndexOf(' ')).firstOrNull { it > max / 2 }?.plus(1) ?: max
        out.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out.filter { it.isNotEmpty() }
}
