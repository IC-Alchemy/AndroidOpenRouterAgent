package com.example.openrouteragent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "openrouter_agent_api_key"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

class AgentStorage internal constructor(context: Context, private val key: () -> SecretKey) {
    constructor(context: Context) : this(context, ::androidKey)

    private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
    private val workspaceFile = AtomicFile(File(context.noBackupFilesDir, "workspace.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun loadTheme(): String = runCatching {
        prefs.getString("config", null)
            ?.let { JSONObject(it).optJSONObject("sdk")?.optString("theme")?.takeIf(String::isNotBlank) }
    }.getOrNull() ?: "oled"

    fun loadConfig(): AgentConfig {
        val raw = prefs.getString("config", null)
        val saved = raw?.let { json.decodeFromString<AgentConfig>(it) } ?: AgentConfig()
        removeLegacyProviderSettings(raw)
        return saved.copy(apiKey = readSecret("encrypted_key", "key_iv"),
            mcpToken = readSecret("encrypted_mcp_token", "mcp_token_iv"))
    }

    private fun removeLegacyProviderSettings(raw: String?) {
        val obsoleteKeys = prefs.all.keys.filter {
            it.startsWith("encrypted_provider_") || it.startsWith("provider_iv_")
        }
        val legacyConfig = raw?.let(::JSONObject)?.takeIf { it.has("activeProviderId") || it.has("customProviders") }
        if (obsoleteKeys.isEmpty() && legacyConfig == null) return
        val edit = prefs.edit()
        obsoleteKeys.forEach { edit.remove(it) }
        legacyConfig?.let {
            it.remove("activeProviderId")
            it.remove("customProviders")
            edit.putString("config", it.toString())
        }
        edit.apply()
    }

    private fun readSecret(valueKey: String, ivKey: String): String {
        val encrypted = prefs.getString(valueKey, null) ?: return ""
        val iv = prefs.getString(ivKey, null) ?: throw IOException("A saved credential could not be read.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun saveConfig(config: AgentConfig) {
        val clean = config.copy(apiKey = "", mcpToken = "")
        val edit = prefs.edit().putString("config", json.encodeToString(AgentConfig.serializer(), clean))
        putSecret(edit, "encrypted_key", "key_iv", config.apiKey)
        putSecret(edit, "encrypted_mcp_token", "mcp_token_iv", config.mcpToken)
        if (!edit.commit()) throw IOException("Android could not save settings.")
    }

    private fun putSecret(edit: android.content.SharedPreferences.Editor, valueKey: String, ivKey: String, secret: String) {
        if (secret.isEmpty()) {
            edit.remove(valueKey).remove(ivKey)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        edit.putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        edit.putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }

    internal fun githubCredentials() = object : GitHubCredentials {
        override fun read(id: String): String = readSecret("encrypted_github_$id", "github_iv_$id")

        override fun save(id: String, token: String) {
            val edit = prefs.edit()
            putSecret(edit, "encrypted_github_$id", "github_iv_$id", token)
            if (!edit.commit()) throw IOException("Android could not save the GitHub token.")
        }
    }

    fun readLegacyWorkspace(): Workspace? = try {
        json.decodeFromString<Workspace>(workspaceFile.readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        null
    }

    fun removeLegacyWorkspace() = workspaceFile.delete()

}

private fun androidKey(): SecretKey {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
    }.generateKey()
}
