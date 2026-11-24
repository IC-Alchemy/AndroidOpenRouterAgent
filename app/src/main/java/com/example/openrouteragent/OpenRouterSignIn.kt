package com.example.openrouteragent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The deep link the sign-in success page offers to bring the user back to the app. */
const val SIGN_IN_RETURN_SCHEME = "icalchemy-openrouteragent"
const val SIGN_IN_RETURN_HOST = "signed-in"

private const val CALLBACK_PATH = "/callback"
private const val SIGN_IN_TIMEOUT_MILLIS = 10 * 60_000L

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun pkceChallenge(verifier: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/**
 * One sign-in attempt with OpenRouter's OAuth PKCE flow. The browser returns to a one-shot listener on
 * 127.0.0.1, which OpenRouter allows on any port. The code verifier never leaves the app except in the key
 * exchange, and the callback must carry the random state.
 */
class OpenRouterSignIn internal constructor(
    private val http: OkHttpClient,
    private val apiBase: HttpUrl,
    private val authPage: HttpUrl,
    private val appPackage: String,
    random: SecureRandom = SecureRandom(),
) : Closeable {
    constructor(http: OkHttpClient, appPackage: String) : this(http,
        "https://openrouter.ai/api/v1/".toHttpUrl(), "https://openrouter.ai/auth".toHttpUrl(), appPackage)

    internal val verifier = base64Url(ByteArray(48).also(random::nextBytes))
    internal val state = base64Url(ByteArray(24).also(random::nextBytes))
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val callbackUrl = "http://localhost:${server.localPort}$CALLBACK_PATH"

    /** The page to open in the browser. */
    val authorizeUrl: String = authPage.newBuilder()
        .addQueryParameter("callback_url", callbackUrl)
        .addQueryParameter("code_challenge", pkceChallenge(verifier))
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state)
        .addQueryParameter("key_label", "OpenRouter Agent Studio")
        .build().toString()

    /** Waits for the browser to return, then exchanges the code for an API key. */
    suspend fun awaitKey(): String {
        val code = awaitCode()
        return exchange(code)
    }

    internal suspend fun awaitCode(): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SIGN_IN_TIMEOUT_MILLIS
        server.soTimeout = 1_000
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw IOException("Sign-in timed out. Try again.")
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use {
                it.soTimeout = 5_000
                val line = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val outcome = parseCallback(line, state)
                val page = when (outcome) {
                    is CallbackOutcome.Code -> successPage(appPackage)
                    is CallbackOutcome.Failed -> messagePage(outcome.message)
                    CallbackOutcome.Ignored -> null
                }
                val status = if (page == null) "404 Not Found" else "200 OK"
                val body = (page ?: "").toByteArray()
                runCatching {
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .toByteArray())
                        write(body)
                        flush()
                    }
                }
                when (outcome) {
                    is CallbackOutcome.Code -> return@withContext outcome.code
                    is CallbackOutcome.Failed -> if (outcome.final) throw IOException(outcome.message)
                    CallbackOutcome.Ignored -> Unit
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    internal suspend fun exchange(code: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code).put("code_verifier", verifier)
            .put("code_challenge_method", "S256").toString()
        val request = Request.Builder().url(apiBase.resolve("auth/keys")!!).openRouterAttribution()
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("OpenRouter did not issue a key (HTTP ${response.code}" +
                    (reason?.let { ": $it" } ?: "") + "). Try signing in again.")
            }
            runCatching { JSONObject(text).getString("key") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw IOException("OpenRouter returned no key. Try signing in again.")
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

internal sealed interface CallbackOutcome {
    data class Code(val code: String) : CallbackOutcome
    data class Failed(val message: String, val final: Boolean) : CallbackOutcome
    data object Ignored : CallbackOutcome
}

/** Reads the HTTP request line the browser sends to the loopback listener. */
internal fun parseCallback(requestLine: String, expectedState: String): CallbackOutcome {
    val parts = requestLine.split(' ')
    if (parts.size < 2 || parts[0] != "GET") return CallbackOutcome.Ignored
    val url = "http://localhost${parts[1]}".toHttpUrlOrNull() ?: return CallbackOutcome.Ignored
    if (url.encodedPath != CALLBACK_PATH) return CallbackOutcome.Ignored
    val code = url.queryParameter("code")
    // OpenRouter omits state when the user denies access, so a missing code ends the attempt.
    if (code.isNullOrBlank()) return CallbackOutcome.Failed("Sign-in was cancelled. Return to the app and try again.", true)
    if (url.queryParameter("state") != expectedState) {
        return CallbackOutcome.Failed("This sign-in link does not match the request from the app.", false)
    }
    return CallbackOutcome.Code(code)
}

private fun successPage(appPackage: String): String {
    val intent = "intent://$SIGN_IN_RETURN_HOST#Intent;scheme=$SIGN_IN_RETURN_SCHEME;package=$appPackage;end"
    return page("Signed in", "Your OpenRouter key was created. Return to OpenRouter Agent Studio to continue.",
        "<p><a class=\"button\" href=\"$intent\">Return to the app</a></p>" +
            "<script>setTimeout(function(){location.href=${JSONObject.quote(intent)}},300)</script>")
}

private fun messagePage(message: String) = page("Sign-in not completed", message, "")

private fun page(title: String, message: String, extra: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{font-family:system-ui,sans-serif;background:#0b0b10;color:#eee;padding:32px 20px;
max-width:480px;margin:auto}h1{font-size:22px}a.button{display:inline-block;margin-top:12px;padding:12px 20px;
border-radius:24px;background:#cfc4ff;color:#23154f;text-decoration:none;font-weight:600}</style></head>
<body><h1>$title</h1><p>${message.replace("<", "&lt;")}</p>$extra</body></html>"""
