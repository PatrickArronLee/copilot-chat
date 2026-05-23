package com.patrick.copilotchat.data

import android.content.Context
import android.content.SharedPreferences
import com.patrick.copilotchat.api.CopilotApiClient

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("copilot_chat_prefs", Context.MODE_PRIVATE)

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value).apply()

    var selectedModel: String
        get() = prefs.getString("selected_model", CopilotApiClient.DEFAULT_MODEL) ?: CopilotApiClient.DEFAULT_MODEL
        set(value) = prefs.edit().putString("selected_model", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt",
            "You are a helpful AI assistant. Be concise and friendly.") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    val isConfigured: Boolean get() = githubToken.isNotBlank()
}
