package com.patrick.copilotchat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
        messages: List<Pair<String, String>>  // role, content
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
            put("max_tokens", 2048)
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Editor-Version", "vscode/1.85.0")
            .build()

        withContext(Dispatchers.IO) {
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
                            val chunk = JSONObject(data)
                            val delta = chunk
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta")
                            if (delta.has("content")) {
                                emit(delta.getString("content"))
                            }
                        } catch (_: Exception) { /* skip malformed chunks */ }
                    }
                }
            }
        }
    }

    companion object {
        val AVAILABLE_MODELS = listOf(
            "claude-sonnet-4.6" to "Claude Sonnet 4.6 ✦",
            "gpt-5.4-mini" to "GPT-5.4 Mini (Fast)",
            "gpt-5.4" to "GPT-5.4 (Smart)",
            "gpt-4o-mini-2024-07-18" to "GPT-4o Mini",
            "gpt-4o-2024-11-20" to "GPT-4o",
            "claude-haiku-4.5" to "Claude Haiku 4.5 (Fast)",
            "claude-opus-4.5" to "Claude Opus 4.5",
            "gemini-2.5-pro" to "Gemini 2.5 Pro"
        )

        const val DEFAULT_MODEL = "claude-sonnet-4.6"
    }
}

