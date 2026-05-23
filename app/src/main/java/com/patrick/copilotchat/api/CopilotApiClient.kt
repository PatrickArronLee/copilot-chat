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
        // Reasoning models (o1/o3/o4) don't support system messages
        if (systemPrompt.isNotBlank() && !isReasoningModel(model)) {
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
            if (isReasoningModel(model)) {
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
            // GPT-5 family
            "gpt-5.5"        to "GPT-5.5",
            "gpt-5.4"        to "GPT-5.4",
            "gpt-5.3-codex"  to "GPT-5.3 Codex",
            "gpt-5.2-codex"  to "GPT-5.2 Codex",
            "gpt-5.2"        to "GPT-5.2",
            "gpt-5-mini"     to "GPT-5 Mini",
            // GPT-4 family
            "gpt-4.1"        to "GPT-4.1",
            "gpt-4o"         to "GPT-4o",
            "gpt-4o-mini"    to "GPT-4o Mini",
            "gpt-4"          to "GPT-4",
            "gpt-3.5-turbo"  to "GPT-3.5 Turbo",
            // Claude
            "claude-opus-4.7"   to "Claude Opus 4.7",
            "claude-opus-4.5"   to "Claude Opus 4.5",
            "claude-sonnet-4.6" to "Claude Sonnet 4.6 (Default)",
            "claude-sonnet-4.5" to "Claude Sonnet 4.5",
            "claude-haiku-4.5"  to "Claude Haiku 4.5",
            // Gemini
            "gemini-2.5-pro" to "Gemini 2.5 Pro"
        )

        const val DEFAULT_MODEL = "claude-sonnet-4.6"

        /** Reasoning models that reject system messages and use max_completion_tokens */
        fun isReasoningModel(model: String) = model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
    }
}
