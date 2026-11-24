package com.example.openrouteragent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

class OpenRouterSignInTest {
    @Test
    fun pkceChallengeIsBase64UrlSha256WithoutPadding() {
        // Reference value computed with Python hashlib and base64.urlsafe_b64encode.
        assertEquals("cLvtU9NHBRqUtO5NvH58GWk2mLwJG7mjEu4Yanrgwic",
            pkceChallenge("dBjftJeZ4CVP-mJ92K9GBgGAjqvVvq3cmnnLc6Vz0J0"))
    }

    @Test
    fun callbackParsingChecksPathStateAndDenial() {
        assertEquals(CallbackOutcome.Code("abc"), parseCallback("GET /callback?code=abc&state=s1 HTTP/1.1", "s1"))
        assertEquals(CallbackOutcome.Ignored, parseCallback("GET /favicon.ico HTTP/1.1", "s1"))
        assertEquals(CallbackOutcome.Ignored, parseCallback("POST /callback?code=abc&state=s1 HTTP/1.1", "s1"))
        val wrongState = parseCallback("GET /callback?code=abc&state=other HTTP/1.1", "s1")
        assertTrue(wrongState is CallbackOutcome.Failed && !wrongState.final)
        val denied = parseCallback("GET /callback HTTP/1.1", "s1")
        assertTrue(denied is CallbackOutcome.Failed && denied.final)
    }

    @Test
    fun theBrowserCallbackIsExchangedForAKey() {
        val api = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"key":"sk-or-v1-test"}"""))
            start()
        }
        try {
            val signIn = OpenRouterSignIn(OkHttpClient(), api.url("/api/v1/"),
                "https://openrouter.ai/auth".toHttpUrl(), "com.example.app")
            signIn.use {
                val authorize = it.authorizeUrl.toHttpUrl()
                assertEquals(it.callbackUrl, authorize.queryParameter("callback_url"))
                assertEquals("S256", authorize.queryParameter("code_challenge_method"))
                assertEquals(pkceChallenge(it.verifier), authorize.queryParameter("code_challenge"))
                assertTrue(it.verifier !in it.authorizeUrl)
                val port = it.callbackUrl.toHttpUrl().port
                val key = runBlocking {
                    val pending = async(Dispatchers.IO) { it.awaitKey() }
                    // A request with the wrong state is refused, then the real callback arrives.
                    browse(port, "/callback?code=evil&state=wrong")
                    val page = browse(port, "/callback?code=good&state=${authorize.queryParameter("state")}")
                    assertTrue(page.contains("200 OK"))
                    assertTrue(page.contains("intent://signed-in#Intent;scheme=$SIGN_IN_RETURN_SCHEME;package=com.example.app;end"))
                    pending.await()
                }
                assertEquals("sk-or-v1-test", key)
            }
            val exchange = api.takeRequest()
            assertEquals("/api/v1/auth/keys", exchange.path)
            assertEquals(OpenRouterAttribution.REFERER, exchange.getHeader("HTTP-Referer"))
            val body = JSONObject(exchange.body.readUtf8())
            assertEquals("good", body.getString("code"))
            assertEquals(signIn.verifier, body.getString("code_verifier"))
            assertEquals("S256", body.getString("code_challenge_method"))
        } finally {
            api.shutdown()
        }
    }

    @Test(expected = IOException::class)
    fun aRejectedExchangeExplainsItself() {
        val api = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Invalid code"}}"""))
            start()
        }
        try {
            OpenRouterSignIn(OkHttpClient(), api.url("/api/v1/"), "https://openrouter.ai/auth".toHttpUrl(), "p")
                .use { runBlocking { it.exchange("x") } }
        } finally {
            api.shutdown()
        }
    }

    private fun browse(port: Int, path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
}
