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

    var selectedModel: String
        get() {
            val storedModel = prefs.getString("selected_model", CopilotApiClient.DEFAULT_MODEL)
                ?: CopilotApiClient.DEFAULT_MODEL
            val validModels = CopilotApiClient.AVAILABLE_MODELS.map { it.first }
            return if (storedModel in validModels) {
                storedModel
            } else {
                prefs.edit().putString("selected_model", CopilotApiClient.DEFAULT_MODEL).apply()
                CopilotApiClient.DEFAULT_MODEL
            }
        }
        set(value) = prefs.edit().putString("selected_model", value).apply()

    var systemPrompt: String
        get() = prefs.getString(
            "system_prompt",
            "You are a helpful AI assistant. Be concise and friendly."
        ) ?: "You are a helpful AI assistant. Be concise and friendly."
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    val isConfigured: Boolean
        get() = githubToken.isNotBlank()
}
