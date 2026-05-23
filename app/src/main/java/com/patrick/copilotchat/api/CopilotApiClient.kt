package com.patrick.copilotchat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CopilotApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "https://api.githubcopilot.com/chat/completions"

    fun streamMessage(
        token: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): Flow<String> = flow {
        val messagesArray = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.forEach { (role, content) ->
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", true)
            // reasoning models use max_completion_tokens, others use max_tokens
            if (model.startsWith("o3") || model.startsWith("o4")) {
                put("max_completion_tokens", 4096)
            } else {
                put("max_tokens", 2048)
            }
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Editor-Version", "vscode/1.85.0")
            .build()

        // Run blocking OkHttp call directly; flowOn(IO) below ensures the
        // entire flow block (including emit) runs on the IO dispatcher.
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("API error ${response.code}: $errorBody")
        }

        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val choices = JSONObject(data).getJSONArray("choices")
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).getJSONObject("delta")
                        // optString returns "null" string for JSON null — use isNull guard
                        val text = if (!delta.isNull("content")) delta.optString("content", "") else ""
                        if (text.isNotEmpty()) emit(text)
                    } catch (_: Exception) { /* skip malformed chunks */ }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        val AVAILABLE_MODELS = listOf(
            "gpt-4.1" to "GPT-4.1 (Latest)",
            "gpt-4o" to "GPT-4o",
            "gpt-4o-2024-11-20" to "GPT-4o (Nov 2024)",
            "gpt-4.1-mini" to "GPT-4.1 Mini",
            "gpt-4o-mini" to "GPT-4o Mini",
            "o4-mini" to "o4-mini (Reasoning)",
            "o3" to "o3 (Reasoning)",
            "claude-3.7-sonnet" to "Claude 3.7 Sonnet",
            "claude-3.5-sonnet" to "Claude 3.5 Sonnet",
            "claude-3.5-haiku" to "Claude 3.5 Haiku",
            "gemini-2.0-flash" to "Gemini 2.0 Flash"
        )

        const val DEFAULT_MODEL = "gpt-4.1"
    }
}
