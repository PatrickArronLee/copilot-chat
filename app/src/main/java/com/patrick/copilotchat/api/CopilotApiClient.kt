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
                            val choices = JSONObject(data).getJSONArray("choices")
                            if (choices.length() == 0) continue
                            val delta = choices.getJSONObject(0).getJSONObject("delta")
                            // use optString so null JSON values become "" not "null"
                            val text = delta.optString("content", "")
                            if (text.isNotEmpty()) emit(text)
                        } catch (_: Exception) { /* skip malformed chunks */ }
                    }
                }
            }
        }
    }

    companion object {
        val AVAILABLE_MODELS = listOf(
            "gpt-4o" to "GPT-4o (Smart)",
            "gpt-4o-2024-11-20" to "GPT-4o (Nov 2024)",
            "gpt-4o-mini" to "GPT-4o Mini (Fast)",
            "gpt-4o-mini-2024-07-18" to "GPT-4o Mini (Jul 2024)",
            "gpt-3.5-turbo" to "GPT-3.5 Turbo"
        )

        const val DEFAULT_MODEL = "gpt-4o"
    }
}

