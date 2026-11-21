package com.example.openrouteragent

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val READ_TIMEOUT_SECONDS = 90L
private val jsonType = "application/json; charset=utf-8".toMediaType()

/** A pull-based byte transport. The SDK still owns decoding events and orchestrating turns. */
internal class AgentHttpTransport(
    private val endpoint: HttpUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val encode: (ByteArray, Int) -> String = { buffer, size ->
        Base64.encodeToString(buffer, 0, size, Base64.NO_WRAP)
    },
) {
    private data class Key(val run: String, val request: String)
    private class Exchange(val call: Call, val emit: (String, Any) -> Unit) {
        @Volatile var response: Response? = null
        val reading = AtomicBoolean()
    }

    private val active = ConcurrentHashMap<Key, Exchange>()

    fun start(runId: String, requestId: String, apiKey: String, body: String, emit: (String, Any) -> Unit) {
        val key = Key(runId, requestId)
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "OpenRouter Sandbox Agent")
            .header("Accept", "text/event-stream, application/json")
            .post(body.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        val exchange = Exchange(call, emit)
        if (active.putIfAbsent(key, exchange) != null) return
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finish(key, exchange, "error", failure(e, beforeHeaders = true))
            }

            override fun onResponse(call: Call, response: Response) {
                exchange.response = response
                if (active[key] !== exchange) {
                    response.close()
                    return
                }
                emit("headers", JSONObject()
                    .put("status", response.code)
                    .put("contentType", response.header("Content-Type") ?: "application/octet-stream")
                    .put("retryAfter", response.header("Retry-After")))
            }
        })
    }

    fun read(runId: String, requestId: String) {
        val key = Key(runId, requestId)
        val exchange = active[key] ?: return
        val response = exchange.response ?: return
        if (!exchange.reading.compareAndSet(false, true)) return
        client.dispatcher.executorService.execute {
            try {
                val bytes = ByteArray(8192)
                val count = response.body?.source()?.read(bytes) ?: -1
                exchange.reading.set(false)
                if (count < 0) finish(key, exchange, "end", JSONObject.NULL)
                else if (active[key] === exchange) exchange.emit("chunk", encode(bytes, count))
            } catch (e: IOException) {
                exchange.reading.set(false)
                finish(key, exchange, "error", failure(e, beforeHeaders = false))
            }
        }
    }

    fun cancel(runId: String, requestId: String) {
        val exchange = active.remove(Key(runId, requestId)) ?: return
        exchange.call.cancel()
        client.dispatcher.executorService.execute { exchange.response?.close() }
    }

    fun cancelRun(runId: String) {
        active.keys.filter { it.run == runId }.forEach { cancel(it.run, it.request) }
    }

    private fun finish(key: Key, exchange: Exchange, kind: String, value: Any) {
        if (!active.remove(key, exchange)) return
        exchange.response?.close()
        exchange.emit(kind, value)
    }

    private fun failure(error: IOException, beforeHeaders: Boolean): String = when (error) {
        is SocketTimeoutException -> if (beforeHeaders) "OpenRouter did not open a stream in time."
            else "OpenRouter sent no data for $READ_TIMEOUT_SECONDS seconds."
        is java.net.UnknownHostException -> "Android could not reach OpenRouter. Check your network connection."
        is javax.net.ssl.SSLException -> "Android could not establish a secure connection to OpenRouter."
        else -> "The OpenRouter connection closed unexpectedly."
    }
}
