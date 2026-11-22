package com.example.openrouteragent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface ReportResult {
    data object Sent : ReportResult
    data object Unconfigured : ReportResult
    data class Failed(val message: String) : ReportResult
}

class ModerationReportClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    endpointUrl: String? = BuildConfig.MODERATION_REPORT_URL,
) {
    private val targetUrl: HttpUrl? = parseEndpoint(endpointUrl)

    suspend fun submitReport(prompt: String, response: String, reason: String): ReportResult {
        val url = targetUrl ?: return ReportResult.Unconfigured
        val bodyJson = JSONObject()
            .put("prompt", prompt)
            .put("response", response)
            .put("reason", reason)
            .toString()
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReportResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Could not submit report.")
        }
    }

    private suspend fun execute(request: Request): ReportResult =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(ReportResult.Failed(e.message ?: "Network error while submitting report."))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        if (response.isSuccessful) {
                            continuation.resume(ReportResult.Sent)
                        } else {
                            continuation.resume(ReportResult.Failed("Report endpoint returned HTTP ${response.code}."))
                        }
                    }
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseEndpoint(raw: String?): HttpUrl? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) {
                return null
            }
            val isLoopback = parsed.host == "localhost" ||
                parsed.host == "127.0.0.1" ||
                parsed.host == "::1" ||
                runCatching {
                    val local = java.net.InetAddress.getByName("localhost")
                    val lo = java.net.InetAddress.getLoopbackAddress()
                    parsed.host.equals(local.hostName, ignoreCase = true) ||
                        parsed.host.equals(local.canonicalHostName, ignoreCase = true) ||
                        parsed.host.equals(lo.hostName, ignoreCase = true) ||
                        parsed.host.equals(lo.canonicalHostName, ignoreCase = true)
                }.getOrDefault(false)
            return if (parsed.isHttps || isLoopback) parsed else null
        }
    }
}
