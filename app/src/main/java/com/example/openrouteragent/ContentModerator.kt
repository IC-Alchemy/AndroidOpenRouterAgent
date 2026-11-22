package com.example.openrouteragent

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp

interface TextModerator {
    suspend fun isSafe(text: String): Boolean
}

enum class TensorDataType {
    FLOAT32,
    INT32,
    INT8,
    UINT8,
    INT64,
    OTHER,
}

data class TensorSpec(
    val name: String,
    val shape: IntArray,
    val dataType: TensorDataType,
    val scale: Float = 1f,
    val zeroPoint: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TensorSpec) return false
        return name == other.name &&
            shape.contentEquals(other.shape) &&
            dataType == other.dataType &&
            scale == other.scale &&
            zeroPoint == other.zeroPoint
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + shape.contentHashCode()
        result = 31 * result + dataType.hashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + zeroPoint
        return result
    }
}

interface TfliteInferenceEngine : AutoCloseable {
    val inputCount: Int
    val outputCount: Int
    fun inputSpec(index: Int): TensorSpec
    fun outputSpec(index: Int): TensorSpec
    fun run(inputs: Array<Any>, outputs: MutableMap<Int, Any>)
}

class TfliteUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private fun logWarn(tag: String, message: String) {
    runCatching { Log.w(tag, message) }
}

object LiteRtInitializer {
    private val lock = Any()
    @Volatile
    private var initDeferred: CompletableDeferred<Boolean>? = null

    fun initialize(context: Context): CompletableDeferred<Boolean> {
        initDeferred?.let { return it }
        return synchronized(lock) {
            initDeferred?.let { return it }
            val deferred = CompletableDeferred<Boolean>()
            initDeferred = deferred
            try {
                val task = TfLite.initialize(context.applicationContext)
                task.addOnSuccessListener { deferred.complete(true) }
                task.addOnFailureListener { error ->
                    logWarn("LiteRtInitializer", "Play Services LiteRT init failed: ${error.message}")
                    deferred.complete(false)
                }
                task.addOnCanceledListener { deferred.complete(false) }
            } catch (t: Throwable) {
                logWarn("LiteRtInitializer", "Play Services LiteRT unavailable: ${t.message}")
                deferred.complete(false)
            }
            deferred
        }
    }

    suspend fun awaitReady(context: Context, timeoutMillis: Long = 10_000L): Boolean =
        withTimeoutOrNull(timeoutMillis) { initialize(context).await() } ?: false
}

class PlayServicesTfliteEngine private constructor(
    private val interpreter: InterpreterApi,
) : TfliteInferenceEngine {
    override val inputCount: Int
        get() = interpreter.inputTensorCount

    override val outputCount: Int
        get() = interpreter.outputTensorCount

    override fun inputSpec(index: Int): TensorSpec {
        val t = interpreter.getInputTensor(index)
        val q = t.quantizationParams()
        return TensorSpec(
            name = t.name(),
            shape = t.shape(),
            dataType = t.dataType().toTensorDataType(),
            scale = q.scale,
            zeroPoint = q.zeroPoint,
        )
    }

    override fun outputSpec(index: Int): TensorSpec {
        val t = interpreter.getOutputTensor(index)
        val q = t.quantizationParams()
        return TensorSpec(
            name = t.name(),
            shape = t.shape(),
            dataType = t.dataType().toTensorDataType(),
            scale = q.scale,
            zeroPoint = q.zeroPoint,
        )
    }

    override fun run(inputs: Array<Any>, outputs: MutableMap<Int, Any>) {
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        suspend fun createFromAsset(context: Context, assetPath: String): PlayServicesTfliteEngine {
            val ready = LiteRtInitializer.awaitReady(context)
            if (!ready) {
                throw TfliteUnavailableException("Play Services LiteRT runtime is not available on this device.")
            }
            val modelBuffer = withContext(Dispatchers.IO) { loadModelBuffer(context, assetPath) }
            val options = InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            val interpreter = try {
                InterpreterApi.create(modelBuffer, options)
            } catch (e: IllegalStateException) {
                throw TfliteUnavailableException("Could not create Play Services LiteRT interpreter.", e)
            }
            return PlayServicesTfliteEngine(interpreter)
        }

        private fun loadModelBuffer(context: Context, assetPath: String): ByteBuffer {
            return try {
                context.assets.openFd(assetPath).use { afd ->
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                    }
                }
            } catch (_: Exception) {
                val bytes = context.assets.open(assetPath).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            }
        }

        private fun DataType.toTensorDataType(): TensorDataType = when (this) {
            DataType.FLOAT32 -> TensorDataType.FLOAT32
            DataType.INT32 -> TensorDataType.INT32
            DataType.INT8 -> TensorDataType.INT8
            DataType.UINT8 -> TensorDataType.UINT8
            DataType.INT64 -> TensorDataType.INT64
            else -> TensorDataType.OTHER
        }
    }
}

class WordPieceTokenizer(vocabStream: InputStream) {
    private val vocab: Map<String, Int>
    val padId: Int
    val unkId: Int
    val clsId: Int
    val sepId: Int

    init {
        val map = HashMap<String, Int>(32_000)
        vocabStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val token = raw.trimEnd('\r', '\n')
                if (token.isNotEmpty() && !map.containsKey(token)) {
                    map[token] = index
                }
            }
        }
        vocab = map
        padId = vocab["[PAD]"] ?: 0
        unkId = vocab["[UNK]"] ?: 100
        clsId = vocab["[CLS]"] ?: 101
        sepId = vocab["[SEP]"] ?: 102
    }

    fun tokenizeIds(text: String): IntArray {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return IntArray(0)
        val basicTokens = basicTokenize(cleaned)
        if (basicTokens.isEmpty()) return IntArray(0)
        val ids = ArrayList<Int>(basicTokens.size * 2)
        for (word in basicTokens) {
            wordPieceEncode(word, ids)
        }
        return ids.toIntArray()
    }

    fun encodeChunks(text: String, maxSeqLen: Int): List<IntArray> {
        require(maxSeqLen >= 3) { "maxSeqLen must be at least 3" }
        val tokenIds = tokenizeIds(text)
        if (tokenIds.isEmpty()) return emptyList()
        val contentCap = maxSeqLen - 2
        val stride = (contentCap / 2).coerceAtLeast(1)
        val chunks = ArrayList<IntArray>()
        var start = 0
        while (start < tokenIds.size) {
            val end = minOf(start + contentCap, tokenIds.size)
            val seq = IntArray(maxSeqLen) { padId }
            seq[0] = clsId
            var pos = 1
            for (i in start until end) {
                seq[pos++] = tokenIds[i]
            }
            seq[pos] = sepId
            chunks.add(seq)
            if (end >= tokenIds.size) break
            start += stride
        }
        return chunks
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            if (cp == 0 || cp == 0xFFFD || isControlChar(ch)) {
                if (ch == '\t' || ch == '\n' || ch == '\r') sb.append(' ')
                continue
            }
            if (Character.isWhitespace(ch)) {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        val lowered = sb.toString().lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val stripped = StringBuilder(normalized.length)
        for (ch in normalized) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                stripped.append(ch)
            }
        }
        return stripped.toString()
    }

    private fun basicTokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.setLength(0)
            }
        }
        for (ch in text) {
            when {
                Character.isWhitespace(ch) -> flush()
                isChineseChar(ch.code) || isPunctuation(ch) -> {
                    flush()
                    tokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        flush()
        return tokens
    }

    private fun wordPieceEncode(token: String, out: MutableList<Int>) {
        if (token.length > MAX_WORD_CHARS) {
            out.add(unkId)
            return
        }
        var start = 0
        var isBad = false
        val subTokens = ArrayList<Int>(4)
        while (start < token.length) {
            var end = token.length
            var matchedId = -1
            while (start < end) {
                val substr = if (start > 0) "##" + token.substring(start, end) else token.substring(start, end)
                val id = vocab[substr]
                if (id != null) {
                    matchedId = id
                    break
                }
                end--
            }
            if (matchedId == -1) {
                isBad = true
                break
            }
            subTokens.add(matchedId)
            start = end
        }
        if (isBad) {
            out.add(unkId)
        } else {
            out.addAll(subTokens)
        }
    }

    private fun isControlChar(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        return when (Character.getType(ch)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }

    private fun isChineseChar(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F)

    private companion object {
        const val MAX_WORD_CHARS = 100
    }
}

class ContentModerator(
    private val vocabProvider: () -> InputStream,
    private val engineFactory: suspend () -> TfliteInferenceEngine,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TextModerator, AutoCloseable {
    private val mutex = Mutex()

    @Volatile
    private var tokenizer: WordPieceTokenizer? = null

    @Volatile
    private var engine: TfliteInferenceEngine? = null

    @Volatile
    private var runtimeUnavailable = false

    override suspend fun isSafe(text: String): Boolean {
        if (text.isBlank()) return true
        return withContext(dispatcher) {
            if (runtimeUnavailable) return@withContext true
            val (tok, eng) = try {
                ensureInitialized()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logWarn("ContentModerator", "LiteRT unavailable; skipping on-device moderation: ${t.message}")
                runtimeUnavailable = true
                return@withContext true
            }
            val inputSpec0 = eng.inputSpec(0)
            val maxSeqLen = inputSpec0.shape.lastOrNull()?.takeIf { it >= 3 } ?: DEFAULT_MAX_SEQ_LEN
            val chunks = tok.encodeChunks(text, maxSeqLen)
            if (chunks.isEmpty()) return@withContext true

            val outSpec = eng.outputSpec(0)
            val numClasses = outSpec.shape.lastOrNull()?.coerceAtLeast(1) ?: 1

            mutex.withLock {
                for (chunk in chunks) {
                    val inputs = buildInputs(eng, chunk, tok.padId, maxSeqLen)
                    val outBuffer: Any = when (outSpec.dataType) {
                        TensorDataType.INT8, TensorDataType.UINT8 -> Array(1) { ByteArray(numClasses) }
                        TensorDataType.FLOAT32 -> Array(1) { FloatArray(numClasses) }
                        else -> error("Unsupported output tensor data type: ${outSpec.dataType}")
                    }
                    val outputs = HashMap<Int, Any>(1).apply { put(0, outBuffer) }
                    eng.run(inputs, outputs)
                    val prob = extractUnsafeProbability(outBuffer, outSpec, numClasses)
                    if (!prob.isNaN() && prob > threshold) {
                        return@withContext false
                    }
                }
                true
            }
        }
    }

    internal suspend fun unsafeProbability(text: String): Float {
        if (text.isBlank()) return 0f
        return withContext(dispatcher) {
            val (tok, eng) = ensureInitialized()
            val inputSpec0 = eng.inputSpec(0)
            val maxSeqLen = inputSpec0.shape.lastOrNull()?.takeIf { it >= 3 } ?: DEFAULT_MAX_SEQ_LEN
            val chunks = tok.encodeChunks(text, maxSeqLen)
            if (chunks.isEmpty()) return@withContext 0f
            val outSpec = eng.outputSpec(0)
            val numClasses = outSpec.shape.lastOrNull()?.coerceAtLeast(1) ?: 1

            mutex.withLock {
                var maxProb = 0f
                for (chunk in chunks) {
                    val inputs = buildInputs(eng, chunk, tok.padId, maxSeqLen)
                    val outBuffer: Any = when (outSpec.dataType) {
                        TensorDataType.INT8, TensorDataType.UINT8 -> Array(1) { ByteArray(numClasses) }
                        TensorDataType.FLOAT32 -> Array(1) { FloatArray(numClasses) }
                        else -> error("Unsupported output tensor data type: ${outSpec.dataType}")
                    }
                    val outputs = HashMap<Int, Any>(1).apply { put(0, outBuffer) }
                    eng.run(inputs, outputs)
                    val prob = extractUnsafeProbability(outBuffer, outSpec, numClasses)
                    if (!prob.isNaN() && prob > maxProb) {
                        maxProb = prob
                    }
                }
                maxProb
            }
        }
    }

    private suspend fun ensureInitialized(): Pair<WordPieceTokenizer, TfliteInferenceEngine> {
        val currentTok = tokenizer
        val currentEng = engine
        if (currentTok != null && currentEng != null) {
            return currentTok to currentEng
        }
        return mutex.withLock {
            val tok = tokenizer ?: WordPieceTokenizer(vocabProvider()).also { tokenizer = it }
            val eng = engine ?: engineFactory().also { engine = it }
            tok to eng
        }
    }

    private fun buildInputs(
        eng: TfliteInferenceEngine,
        inputIds: IntArray,
        padId: Int,
        maxSeqLen: Int,
    ): Array<Any> {
        val count = eng.inputCount
        if (count <= 1) {
            return arrayOf(arrayOf(inputIds))
        }
        return Array(count) { idx ->
            val name = eng.inputSpec(idx).name.lowercase(Locale.ROOT)
            val arr = when {
                "mask" in name -> IntArray(maxSeqLen) { pos -> if (inputIds[pos] != padId) 1 else 0 }
                "type" in name || "segment" in name -> IntArray(maxSeqLen) { 0 }
                else -> inputIds
            }
            arrayOf(arr)
        }
    }

    private fun extractUnsafeProbability(
        outBuffer: Any,
        outSpec: TensorSpec,
        numClasses: Int,
    ): Float {
        val logitsOrProbs = when (outSpec.dataType) {
            TensorDataType.INT8 -> {
                @Suppress("UNCHECKED_CAST")
                val bytes = (outBuffer as Array<ByteArray>)[0]
                FloatArray(numClasses) { i -> (bytes[i].toInt() - outSpec.zeroPoint) * outSpec.scale }
            }
            TensorDataType.UINT8 -> {
                @Suppress("UNCHECKED_CAST")
                val bytes = (outBuffer as Array<ByteArray>)[0]
                FloatArray(numClasses) { i -> ((bytes[i].toInt() and 0xFF) - outSpec.zeroPoint) * outSpec.scale }
            }
            TensorDataType.FLOAT32 -> {
                @Suppress("UNCHECKED_CAST")
                (outBuffer as Array<FloatArray>)[0]
            }
            else -> error("Unsupported output tensor data type: ${outSpec.dataType}")
        }
        return if (numClasses == 1) {
            logitsOrProbs[0].coerceIn(0f, 1f)
        } else {
            val maxVal = logitsOrProbs.maxOrNull() ?: 0f
            var sumExp = 0.0
            val exps = DoubleArray(numClasses)
            for (i in 0 until numClasses) {
                val e = exp((logitsOrProbs[i] - maxVal).toDouble())
                exps[i] = e
                sumExp += e
            }
            if (sumExp == 0.0) 0f else (exps[1] / sumExp).toFloat()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.85f
        const val DEFAULT_MAX_SEQ_LEN = 128
        const val DEFAULT_MODEL_ASSET = "moderation/text_moderation_int8.tflite"
        const val DEFAULT_VOCAB_ASSET = "moderation/vocab.txt"

        fun fromAssets(
            context: Context,
            modelAssetPath: String = DEFAULT_MODEL_ASSET,
            vocabAssetPath: String = DEFAULT_VOCAB_ASSET,
            threshold: Float = DEFAULT_THRESHOLD,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): ContentModerator {
            val appContext = context.applicationContext
            return ContentModerator(
                vocabProvider = { appContext.assets.open(vocabAssetPath) },
                engineFactory = { PlayServicesTfliteEngine.createFromAsset(appContext, modelAssetPath) },
                threshold = threshold,
                dispatcher = dispatcher,
            )
        }
    }
}
