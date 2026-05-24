package com.patrick.copilotchat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Events emitted by the bridge streaming endpoint. */
sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ToolCall(val id: String, val name: String, val args: String) : StreamEvent()
    data class ToolResult(val name: String, val output: String, val isError: Boolean) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    object Done : StreamEvent()
}

class CopilotApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "https://api.githubcopilot.com/chat/completions"

    /** Direct API streaming (no tool calling). Used when bridge is unavailable. */
    fun streamMessage(
        token: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): Flow<String> = flow {
        val messagesArray = JSONArray()
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
            if (isReasoningModel(model)) {
                put("max_completion_tokens", 4096)
            } else {
                put("max_tokens", 2048)
            }
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Editor-Version", "vscode/1.85.0")
            .build()

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
                        val text = if (!delta.isNull("content")) delta.optString("content", "") else ""
                        if (text.isNotEmpty()) emit(text)
                    } catch (_: Exception) { /* skip malformed chunks */ }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Bridge streaming: connects to the local bridge server and parses its SSE events. */
    fun streamBridge(
        bridgeUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        fallbackToken: String = ""
    ): Flow<StreamEvent> = flow {
        val messagesArray = JSONArray()
        messages.forEach { (role, content) ->
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        val bodyObj = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("systemPrompt", systemPrompt)
            if (fallbackToken.isNotBlank()) put("token", fallbackToken.trim())
        }

        val request = Request.Builder()
            .url("$bridgeUrl/chat")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("Bridge error ${response.code}: $errorBody")
        }

        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isBlank()) continue
                try {
                    val obj = JSONObject(data)
                    when (obj.optString("type")) {
                        "text" -> {
                            val text = obj.optString("content", "")
                            if (text.isNotEmpty()) emit(StreamEvent.TextChunk(text))
                        }
                        "tool_call" -> emit(StreamEvent.ToolCall(
                            id = obj.optString("id", ""),
                            name = obj.optString("name", ""),
                            args = obj.optJSONObject("args")?.toString() ?: "{}"
                        ))
                        "tool_result" -> emit(StreamEvent.ToolResult(
                            name = obj.optString("name", ""),
                            output = obj.optString("output", ""),
                            isError = obj.optBoolean("error", false)
                        ))
                        "error" -> emit(StreamEvent.Error(obj.optString("message", "Unknown error")))
                        "done" -> {
                            emit(StreamEvent.Done)
                            break
                        }
                    }
                } catch (_: Exception) { /* skip malformed events */ }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Check if the bridge server is reachable. Returns true if /status responds OK. */
    suspend fun isBridgeAvailable(bridgeUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$bridgeUrl/status")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) { false }
    }

    /** Fetch available models via the bridge server's /models proxy. */
    suspend fun fetchModelsViaBridge(bridgeUrl: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$bridgeUrl/models")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val data = JSONObject(body).getJSONArray("data")
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotBlank()) ids.add(id)
            }
            ids
        } catch (_: Exception) { null }
    }

    /** Fetches the list of model IDs available for [token] from the Copilot API directly.
     *  Returns null on network/auth error. */
    suspend fun fetchAvailableModels(token: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.githubcopilot.com/models")
                .get()
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Editor-Version", "vscode/1.85.0")
                .header("Copilot-Integration-Id", "vscode-chat")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val data = JSONObject(body).getJSONArray("data")
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotBlank()) ids.add(id)
            }
            ids
        } catch (_: Exception) { null }
    }

    companion object {
        val AVAILABLE_MODELS = listOf(
            "claude-sonnet-4.6" to "Claude Sonnet 4.6 (Default)",
            "claude-sonnet-4.5" to "Claude Sonnet 4.5",
            "claude-haiku-4.5"  to "Claude Haiku 4.5",
            "gpt-5.2"        to "GPT-5.2",
            "gpt-5-mini"     to "GPT-5 Mini",
            "gpt-4.1"        to "GPT-4.1",
            "gpt-4o"         to "GPT-4o",
            "gpt-4o-mini"    to "GPT-4o Mini",
            "gpt-4"          to "GPT-4",
            "gpt-3.5-turbo"  to "GPT-3.5 Turbo",
            "gemini-2.5-pro" to "Gemini 2.5 Pro"
        )

        const val DEFAULT_MODEL = "claude-sonnet-4.6"
        const val DEFAULT_BRIDGE_URL = "http://localhost:8765"

        fun isReasoningModel(model: String) =
            model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
    }
}
