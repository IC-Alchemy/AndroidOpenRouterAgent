package com.example.openrouteragent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt

class ContentModeratorTest {

    private fun resolveAssetFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/assets/$relativePath"),
            File("app/src/main/assets/$relativePath"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate asset $relativePath from ${File(".").absolutePath}")
    }

    private fun createRealModerator(threshold: Float = ContentModerator.DEFAULT_THRESHOLD): ContentModerator {
        val modelFile = resolveAssetFile(ContentModerator.DEFAULT_MODEL_ASSET)
        val vocabFile = resolveAssetFile(ContentModerator.DEFAULT_VOCAB_ASSET)
        return ContentModerator(
            vocabProvider = { vocabFile.inputStream() },
            engineFactory = { BytedecoTfliteEngine(modelFile) },
            threshold = threshold,
            dispatcher = Dispatchers.Default,
        )
    }

    @Test
    fun realBundledInt8ModelFlagsToxicAndNsfwAndAllowsBenignOnDefaultDispatcher() = runBlocking {
        createRealModerator().use { moderator ->
            val unsafeInputs = listOf(
                "You are a worthless pathetic idiot and everyone hates you, go die.",
                "Shut the fuck up you stupid piece of shit.",
                "She moaned loudly as he grabbed her naked breasts and fucked her hard in bed.",
                "Suck my hard cock and swallow every drop of cum right now, you dirty slut.",
            )
            for (text in unsafeInputs) {
                val prob = moderator.unsafeProbability(text)
                assertFalse("Expected unsafe=false for '$text' (prob=$prob)", moderator.isSafe(text))
                assertTrue("Expected prob > 0.85 for '$text' (prob=$prob)", prob > 0.85f)
            }

            val benignInputs = listOf(
                "Write a Kotlin function to sort a list of integers.",
                "Explain how photosynthesis works in green plants.",
                "How do I kill a background process in Linux using killall?",
                "Breast cancer awareness and early screening programs save thousands of lives.",
                "Thank you for your help with the presentation today, you did an amazing job!",
            )
            for (text in benignInputs) {
                val prob = moderator.unsafeProbability(text)
                assertTrue("Expected safe=true for '$text' (prob=$prob)", moderator.isSafe(text))
            }
        }
    }

    @Test
    fun emptyAndWhitespaceStringsAreSafe() = runBlocking {
        createRealModerator().use { moderator ->
            assertTrue(moderator.isSafe(""))
            assertTrue(moderator.isSafe("   \n\t   "))
            assertTrue(moderator.isSafe("\u200B\uFEFF"))
        }
    }

    @Test
    fun longInputWithBenignPrefixAndUnsafeTailIsFlagged() = runBlocking {
        createRealModerator().use { moderator ->
            val benignPrefix = List(160) { "weather" }.joinToString(" ")
            val longUnsafe = "$benignPrefix. You are a fucking disgusting piece of trash, kill yourself."
            assertFalse(moderator.isSafe(longUnsafe))
        }
    }

    @Test
    fun thresholdBoundaryAndInt8DequantizationBehaveCorrectly() = runBlocking {
        val vocabFile = resolveAssetFile(ContentModerator.DEFAULT_VOCAB_ASSET)
        var currentProb = 0.85f
        val scale = 1f / 255f
        val zeroPoint = -128
        val fakeInt8Engine = object : TfliteInferenceEngine {
            override val inputCount = 1
            override val outputCount = 1
            override fun inputSpec(index: Int) = TensorSpec("input_ids", intArrayOf(1, 16), TensorDataType.INT32)
            override fun outputSpec(index: Int) = TensorSpec("unsafe_prob", intArrayOf(1, 1), TensorDataType.INT8, scale, zeroPoint)
            override fun run(inputs: Array<Any>, outputs: MutableMap<Int, Any>) {
                @Suppress("UNCHECKED_CAST")
                val out = outputs[0] as Array<ByteArray>
                val q = ((currentProb / scale) + zeroPoint).roundToInt().coerceIn(-128, 127).toByte()
                out[0][0] = q
            }
            override fun close() {}
        }
        ContentModerator(
            vocabProvider = { vocabFile.inputStream() },
            engineFactory = { fakeInt8Engine },
            threshold = 0.85f,
            dispatcher = Dispatchers.Default,
        ).use { moderator ->
            // Quantized value just below/at 0.85f is safe
            currentProb = 0.847f
            assertTrue(moderator.isSafe("test boundary safe"))

            // Quantized value above 0.85f is flagged
            currentProb = 0.86f
            assertFalse(moderator.isSafe("test boundary unsafe"))
        }

        // Exact float boundary check (0.85f == safe, 0.8501f == unsafe)
        var floatProb = 0.85f
        val fakeFloatEngine = object : TfliteInferenceEngine {
            override val inputCount = 1
            override val outputCount = 1
            override fun inputSpec(index: Int) = TensorSpec("input_ids", intArrayOf(1, 16), TensorDataType.INT32)
            override fun outputSpec(index: Int) = TensorSpec("unsafe_prob", intArrayOf(1, 1), TensorDataType.FLOAT32)
            override fun run(inputs: Array<Any>, outputs: MutableMap<Int, Any>) {
                @Suppress("UNCHECKED_CAST")
                val out = outputs[0] as Array<FloatArray>
                out[0][0] = floatProb
            }
            override fun close() {}
        }
        ContentModerator(
            vocabProvider = { vocabFile.inputStream() },
            engineFactory = { fakeFloatEngine },
            threshold = 0.85f,
            dispatcher = Dispatchers.Default,
        ).use { moderator ->
            floatProb = 0.85f
            assertTrue(moderator.isSafe("exact boundary"))
            floatProb = 0.8501f
            assertFalse(moderator.isSafe("above boundary"))
        }
    }

    @Test
    fun concurrentIsSafeCallsSerializeSafelyWithoutCrashing() = runBlocking {
        createRealModerator().use { moderator ->
            val inputs = listOf(
                "Explain how HTTP caching works." to true,
                "You are a worthless piece of shit, go die." to false,
                "Write unit tests for a Kotlin repository." to true,
                "She sucked his hard cock furiously." to false,
            )
            val results = (0 until 12).map { idx ->
                val (text, expected) = inputs[idx % inputs.size]
                async(Dispatchers.Default) {
                    moderator.isSafe(text) to expected
                }
            }.awaitAll()
            results.forEach { (actual, expected) ->
                assertEquals(expected, actual)
            }
        }
    }

    @Test
    fun missingAssetsAndUnavailableEngineFailOpenAndCacheUnavailableState() = runBlocking {
        var vocabAttempts = 0
        ContentModerator(
            vocabProvider = {
                vocabAttempts++
                throw FileNotFoundException("missing vocab.txt")
            },
            engineFactory = { error("Should not be reached") },
            dispatcher = Dispatchers.Default,
        ).use { moderator ->
            assertTrue(moderator.isSafe("First check"))
            assertTrue(moderator.isSafe("Second check"))
            assertEquals(1, vocabAttempts)
        }

        val vocabFile = resolveAssetFile(ContentModerator.DEFAULT_VOCAB_ASSET)
        var engineAttempts = 0
        ContentModerator(
            vocabProvider = { vocabFile.inputStream() },
            engineFactory = {
                engineAttempts++
                throw TfliteUnavailableException("Play Services unavailable")
            },
            dispatcher = Dispatchers.Default,
        ).use { moderator ->
            assertTrue(moderator.isSafe("First check"))
            assertTrue(moderator.isSafe("Second check"))
            assertEquals(1, engineAttempts)
        }

        var runtimeAttempts = 0
        ContentModerator(
            vocabProvider = { vocabFile.inputStream() },
            engineFactory = {
                runtimeAttempts++
                throw RuntimeException("Unexpected native init failure")
            },
            dispatcher = Dispatchers.Default,
        ).use { moderator ->
            assertTrue(moderator.isSafe("First check"))
            assertTrue(moderator.isSafe("Second check"))
            assertEquals(1, runtimeAttempts)
        }
    }
}
