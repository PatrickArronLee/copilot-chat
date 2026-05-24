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
        put(JSONObject("""{"type":"function","function":{"name":"search_files","description":"Search for a pattern in files using grep. Returns matching lines with filenames.","parameters":{"type":"object","properties":{"pattern":{"type":"string","description":"The grep pattern to search for"},"path":{"type":"string","description":"Directory or file to search in (default: current directory)"}},"required":["pattern"]}}}"""))
        put(JSONObject("""{"type":"function","function":{"name":"append_file","description":"Append content to the end of a file, creating it if it doesn't exist.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}}"""))
        put(JSONObject("""{"type":"function","function":{"name":"http_get","description":"Make an HTTP GET request and return the response body (max 2000 chars).","parameters":{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch"}},"required":["url"]}}}"""))
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
                "search_files" -> {
                    val pattern = args.optString("pattern", "")
                    val searchPath = expandPath(args.optString("path", "."))
                    val process = ProcessBuilder(findShell(), "-c", "grep -r -n --include='*' '${pattern.replace("'", "'\\''")}' '$searchPath' 2>&1 | head -50")
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    Pair(output.ifBlank { "(no matches)" }, false)
                }
                "append_file" -> {
                    val path = expandPath(args.optString("path", ""))
                    val content = args.optString("content", "")
                    File(path).also { it.parentFile?.mkdirs() }.appendText(content)
                    Pair("Appended ${content.length} chars to $path", false)
                }
                "http_get" -> {
                    val url = args.optString("url", "")
                    val process = ProcessBuilder(findShell(), "-c", "curl -s --max-time 10 '${url.replace("'", "'\\''")}' 2>&1 | head -c 2000")
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    Pair(output.ifBlank { "(empty response)" }, false)
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

    private fun formatToolArgs(name: String, argsJson: String): String {
        return try {
            val a = JSONObject(argsJson)
            when (name) {
                "bash" -> a.optString("command", argsJson)
                "read_file", "list_files" -> a.optString("path", argsJson)
                "write_file", "append_file" -> a.optString("path", argsJson)
                "search_files" -> {
                    val p = a.optString("pattern", "")
                    val path = a.optString("path", ".")
                    if (path == ".") p else "$p in $path"
                }
                "http_get" -> a.optString("url", argsJson)
                else -> argsJson
            }
        } catch (_: Exception) { argsJson }
    }

    // ─── Embedded agentic loop (no bridge needed) ─────────────────────────────

    // ─── Embedded agentic loop using STREAMING endpoint ──────────────────────
    // Uses stream:true (same as plain chat, known to work) and accumulates
    // tool_call delta fragments before executing locally.

    private data class ToolCallAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    /** Agentic loop via streaming endpoint. Falls back to plain chat on any error. */
    fun streamAgenticLoop(
        token: String,
        model: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>
    ): Flow<StreamEvent> = flow {
        val apiMessages = JSONArray()
        if (systemPrompt.isNotBlank() && !isReasoningModel(model)) {
            apiMessages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        }
        messages.forEach { (role, content) ->
            apiMessages.put(JSONObject().apply { put("role", role); put("content", content) })
        }

        for (iteration in 0 until 10) {
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", apiMessages)
                put("stream", true)
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
                // On first call 400, fall back to plain streaming (tools not supported by token)
                if (response.code == 400 && iteration == 0) {
                    emit(StreamEvent.TextChunk("⚠️ *Tool calling not available with this token — using plain chat.*\n\n"))
                    streamMessage(token, model, systemPrompt, messages).collect { emit(StreamEvent.TextChunk(it)) }
                    emit(StreamEvent.Done)
                    return@flow
                }
                emit(StreamEvent.Error("API error ${response.code}: $errBody"))
                return@flow
            }

            // Accumulate stream: text chunks + tool_call fragments
            val toolAccs = mutableMapOf<Int, ToolCallAcc>()
            val assistantText = StringBuilder()
            var finishReason = "stop"

            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotEmpty()) finishReason = fr
                    val delta = choice.optJSONObject("delta") ?: continue

                    // Text content
                    if (!delta.isNull("content")) {
                        val text = delta.optString("content", "")
                        if (text.isNotEmpty()) {
                            assistantText.append(text)
                            emit(StreamEvent.TextChunk(text))
                        }
                    }

                    // Tool call fragments
                    val tcDeltas = delta.optJSONArray("tool_calls")
                    if (tcDeltas != null) {
                        for (i in 0 until tcDeltas.length()) {
                            val tc = tcDeltas.getJSONObject(i)
                            val idx = tc.optInt("index", 0)
                            val acc = toolAccs.getOrPut(idx) { ToolCallAcc() }
                            if (tc.has("id")) acc.id = tc.optString("id")
                            val fn = tc.optJSONObject("function") ?: continue
                            if (fn.has("name")) acc.name = fn.optString("name", "")
                            acc.args.append(fn.optString("arguments", ""))
                        }
                    }
                }
            }

            if (finishReason == "tool_calls" && toolAccs.isNotEmpty()) {
                // Rebuild assistant message with tool_calls for history
                val tcArray = JSONArray()
                toolAccs.entries.sortedBy { it.key }.forEach { (_, acc) ->
                    tcArray.put(JSONObject().apply {
                        put("id", acc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", acc.name)
                            put("arguments", acc.args.toString())
                        })
                    })
                }
                apiMessages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", if (assistantText.isEmpty()) "" else assistantText.toString())
                    put("tool_calls", tcArray)
                })

                // Execute each tool and add results
                toolAccs.entries.sortedBy { it.key }.forEach { (_, acc) ->
                    emit(StreamEvent.ToolCall(id = acc.id, name = acc.name, args = formatToolArgs(acc.name, acc.args.toString())))
                    val (output, isError) = executeToolLocally(acc.name, acc.args.toString())
                    emit(StreamEvent.ToolResult(name = acc.name, output = output, isError = isError))
                    apiMessages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", acc.id)
                        put("content", output)
                    })
                }
                // Continue loop with tool results
            } else {
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
