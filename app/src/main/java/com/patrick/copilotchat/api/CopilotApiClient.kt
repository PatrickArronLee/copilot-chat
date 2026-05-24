package com.patrick.copilotchat.api

import android.util.Log
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
import java.io.File
import java.util.concurrent.TimeUnit

/** Events emitted by the streaming / agentic loop. */
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

    // ─── Tool definitions sent to the API ────────────────────────────────────

    private val toolDefs = JSONArray().apply {
        put(JSONObject("""{"type":"function","function":{"name":"bash","description":"Execute a shell command. Use for file ops, running programs, checking system state.","parameters":{"type":"object","properties":{"command":{"type":"string","description":"The bash command to execute"}},"required":["command"]}}}"""))
        put(JSONObject("""{"type":"function","function":{"name":"read_file","description":"Read the full contents of a file.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Absolute or ~ path to read"}},"required":["path"]}}}"""))
        put(JSONObject("""{"type":"function","function":{"name":"write_file","description":"Write content to a file, creating parent dirs if needed.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}}"""))
        put(JSONObject("""{"type":"function","function":{"name":"list_files","description":"List files and directories at a path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Directory path to list"}},"required":["path"]}}}"""))
    }

    // ─── Local tool execution ─────────────────────────────────────────────────

    private fun executeToolLocally(name: String, argsJson: String): Pair<String, Boolean> {
        return try {
            val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
            when (name) {
                "bash" -> {
                    val command = args.optString("command", "")
                    val shell = findShell()
                    val process = ProcessBuilder(shell, "-c", command)
                        .redirectErrorStream(false)
                        .start()
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    val exited = process.waitFor()
                    var output = stdout.trim()
                    if (stderr.isNotBlank()) output += (if (output.isNotEmpty()) "\nSTDERR:\n" else "") + stderr.trim()
                    if (exited != 0) output += "\n[exit $exited]"
                    Pair(output.ifBlank { "(no output)" }, exited != 0)
                }
                "read_file" -> {
                    val path = expandPath(args.optString("path", ""))
                    Pair(File(path).readText(), false)
                }
                "write_file" -> {
                    val path = expandPath(args.optString("path", ""))
                    val content = args.optString("content", "")
                    File(path).also { it.parentFile?.mkdirs() }.writeText(content)
                    Pair("Written ${content.length} bytes to $path", false)
                }
                "list_files" -> {
                    val path = expandPath(args.optString("path", "."))
                    val entries = File(path).listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                        ?.joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }
                        ?: "(empty)"
                    Pair(entries, false)
                }
                else -> Pair("Unknown tool: $name", true)
            }
        } catch (e: Exception) {
            Pair("Error: ${e.message}", true)
        }
    }

    private fun findShell(): String {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/sh",
            "/system/bin/sh",
            "/bin/sh"
        )
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    private fun expandPath(path: String): String {
        if (path.startsWith("~")) {
            val home = System.getProperty("user.home") ?: "/data/data/com.termux/files/home"
            return home + path.substring(1)
        }
        return path
    }

    // ─── Embedded agentic loop (no bridge needed) ─────────────────────────────

    /** Full agentic loop running entirely inside the APK. Emits StreamEvents. */
    fun streamAgenticLoop(
        token: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): Flow<StreamEvent> = flow {
        val apiMessages = JSONArray()
        if (systemPrompt.isNotBlank() && !isReasoningModel(model)) {
            apiMessages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.forEach { (role, content) ->
            apiMessages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        for (iteration in 0 until 10) {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", apiMessages)
                put("stream", false)
                if (isReasoningModel(model)) {
                    put("max_completion_tokens", 4096)
                } else {
                    put("max_tokens", 4096)
                    put("tools", toolDefs)
                    put("tool_choice", "auto")
                }
            }

            val request = Request.Builder()
                .url(apiUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Content-Type", "application/json")
                .header("Copilot-Integration-Id", "vscode-chat")
                .header("Editor-Version", "vscode/1.85.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "Unknown error"
                emit(StreamEvent.Error("API error ${response.code}: $errBody"))
                return@flow
            }

            val body = JSONObject(response.body!!.string())
            val choice = body.getJSONArray("choices").getJSONObject(0)
            val finishReason = choice.optString("finish_reason", "stop")
            val message = choice.getJSONObject("message")

            apiMessages.put(message)

            if (finishReason == "tool_calls") {
                val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    val toolName = fn.optString("name", "")
                    val toolArgs = fn.optString("arguments", "{}")
                    val toolId = tc.optString("id", "")

                    emit(StreamEvent.ToolCall(id = toolId, name = toolName, args = toolArgs))

                    val (output, isError) = executeToolLocally(toolName, toolArgs)

                    emit(StreamEvent.ToolResult(name = toolName, output = output, isError = isError))

                    apiMessages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolId)
                        put("content", output)
                    })
                }
            } else {
                val content = message.optString("content", "")
                if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
                emit(StreamEvent.Done)
                return@flow
            }
        }
        emit(StreamEvent.Error("Reached maximum tool call iterations (10)"))
    }.flowOn(Dispatchers.IO)

    // ─── Direct streaming (no tools) ─────────────────────────────────────────

    /** Direct API streaming (no tool calling). */
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
                    } catch (_: Exception) {}
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ─── Bridge passthrough (optional, if bridge server is running) ───────────

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
                        "done" -> { emit(StreamEvent.Done); break }
                    }
                } catch (_: Exception) {}
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun isBridgeAvailable(bridgeUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$bridgeUrl/status").get().build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) { false }
    }

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
            (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf { s -> s.isNotBlank() } }
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
