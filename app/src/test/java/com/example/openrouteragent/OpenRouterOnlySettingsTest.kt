package com.example.openrouteragent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterOnlySettingsTest {
    @Test
    fun legacyProviderSettingsDoNotOverrideOpenRouterOrRequireOldCredentials() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val storage = testAgentStorage(context)
        storage.saveConfig(AgentConfig(apiKey = "saved-openrouter-key", mcpToken = "saved-mcp-token"))
        prefs.edit()
            .putString("config", """{"activeProviderId":"legacy","customProviders":[{"id":"legacy"}],"model":"openai/gpt-4o-mini","enableModeration":true}""")
            .putString("encrypted_provider_legacy", "unreadable-old-secret")
            .putString("provider_iv_orphan", "old-iv")
            .putString("encrypted_github_project", "project-credential")
            .commit()

        val config = storage.loadConfig()
        assertEquals("saved-openrouter-key", config.apiKey)
        assertEquals("saved-mcp-token", config.mcpToken)
        assertEquals("openrouter", chatScope(config).provider)
        assertEquals("openai/gpt-4o-mini", config.model)
        assertTrue(config.enableModeration)
        assertFalse(prefs.contains("encrypted_provider_legacy"))
        assertFalse(prefs.contains("provider_iv_orphan"))
        assertFalse(prefs.getString("config", "").orEmpty().contains("customProviders"))
        assertEquals("project-credential", prefs.getString("encrypted_github_project", null))
    }
}
