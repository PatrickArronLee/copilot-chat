package com.patrick.copilotchat.data

import android.content.Context
import android.content.SharedPreferences
import com.patrick.copilotchat.api.CopilotApiClient

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("copilot_chat_prefs", Context.MODE_PRIVATE)

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value.trim()).apply()

    /** Model IDs confirmed available for the current token (fetched on save). Empty = unknown. */
    var tokenModelIds: List<String>
        get() {
            val stored = prefs.getString("token_model_ids", "") ?: ""
            return if (stored.isBlank()) emptyList() else stored.split(",")
        }
        set(value) = prefs.edit().putString("token_model_ids", value.joinToString(",")).apply()

    /** Models from AVAILABLE_MODELS that are supported by the current token.
     *  Falls back to full AVAILABLE_MODELS list if token models haven't been fetched yet. */
    val supportedModels: List<Pair<String, String>>
        get() {
            val ids = tokenModelIds
            if (ids.isEmpty()) return CopilotApiClient.AVAILABLE_MODELS
            return CopilotApiClient.AVAILABLE_MODELS.filter { (id, _) -> id in ids }
                .ifEmpty { CopilotApiClient.AVAILABLE_MODELS.filter { (id, _) -> id == "gpt-4o" } }
        }

    var selectedModel: String
        get() {
            val storedModel = prefs.getString("selected_model", CopilotApiClient.DEFAULT_MODEL)
                ?: CopilotApiClient.DEFAULT_MODEL
            val validModels = supportedModels.map { it.first }
            return if (storedModel in validModels) storedModel else {
                val fallback = validModels.firstOrNull() ?: "gpt-4o"
                prefs.edit().putString("selected_model", fallback).apply()
                fallback
            }
        }
        set(value) = prefs.edit().putString("selected_model", value).apply()

    var systemPrompt: String
        get() = prefs.getString(
            "system_prompt",
            "You are a helpful AI assistant. Be concise and friendly."
        ) ?: "You are a helpful AI assistant. Be concise and friendly."
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    /** URL of the local Copilot bridge server (default: localhost:8765). */
    var bridgeUrl: String
        get() = prefs.getString("bridge_url", "http://localhost:8765") ?: "http://localhost:8765"
        set(value) = prefs.edit().putString("bridge_url", value.trimEnd('/')).apply()

    val isConfigured: Boolean
        get() = githubToken.isNotBlank()
}
