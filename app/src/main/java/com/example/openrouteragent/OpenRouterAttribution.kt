package com.example.openrouteragent

import okhttp3.Request

/**
 * OpenRouter app attribution (https://openrouter.ai/docs/app-attribution).
 *
 * HTTP-Referer is what makes OpenRouter create an app page and count the app's usage in its
 * public rankings and on model pages; a title header on its own does not create an app entry.
 * Every request this app sends to openrouter.ai goes through [openRouterAttribution], so the
 * referer and title only need to change here.
 */
object OpenRouterAttribution {
    /** Public URL that identifies the app on OpenRouter. Shared with the Windows app so usage adds up to one app. */
    const val REFERER = "https://icalchemy.com"

    /** Display name on OpenRouter's app page and rankings. */
    const val TITLE = "OpenRouter Sandbox Agent"
}

/** Adds OpenRouter's app attribution headers (HTTP-Referer and X-OpenRouter-Title). */
fun Request.Builder.openRouterAttribution(): Request.Builder = this
    .header("HTTP-Referer", OpenRouterAttribution.REFERER)
    .header("X-OpenRouter-Title", OpenRouterAttribution.TITLE)
