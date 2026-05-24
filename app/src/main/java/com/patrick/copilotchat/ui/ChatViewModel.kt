package com.patrick.copilotchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.api.StreamEvent
import com.patrick.copilotchat.data.AppPreferences
import com.patrick.copilotchat.data.Conversation
import com.patrick.copilotchat.data.ConversationRepository
import com.patrick.copilotchat.data.Message
import com.patrick.copilotchat.data.MessageRole
import com.patrick.copilotchat.data.ToolEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val prefs: AppPreferences,
    private val repository: ConversationRepository
) : ViewModel() {

    private val api = CopilotApiClient()

    private val _conversations = MutableStateFlow(repository.loadAll().sortedByDescending { it.createdAt })
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _activeConversation = MutableStateFlow(_conversations.value.firstOrNull())
    val activeConversation: StateFlow<Conversation?> = _activeConversation

    private val _messages = MutableStateFlow(_activeConversation.value?.messages ?: emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentModel = MutableStateFlow(resolveModel(_activeConversation.value?.modelId ?: prefs.selectedModel))
    val currentModel: StateFlow<String> = _currentModel

    /** "⚡ Bridge" badge — shown when bridge is online (optional enhancement). */
    private val _bridgeActive = MutableStateFlow(false)
    val bridgeActive: StateFlow<Boolean> = _bridgeActive

    val supportedModels: List<Pair<String, String>>
        get() = prefs.supportedModels

    private var currentRequestJob: Job? = null
    private var streamingConversationId: String? = null

    init {
        checkBridge()
    }

    fun checkBridge() {
        viewModelScope.launch {
            _bridgeActive.value = api.isBridgeAvailable(prefs.bridgeUrl)
        }
    }

    fun sendMessage(text: String) {
        val userText = text.trim()
        if (userText.isBlank() || _isLoading.value) return
        if (userText.startsWith("/")) { handleSlashCommand(userText); return }
        if (!prefs.isConfigured) {
            _error.value = "Please add your GitHub token in Settings"
            return
        }

        val baseConversation = _activeConversation.value ?: Conversation(modelId = _currentModel.value)
        val title = if (baseConversation.title == "New Chat" || baseConversation.title.isBlank()) {
            repository.generateTitle(userText)
        } else baseConversation.title

        val userMessage = Message(role = MessageRole.USER, content = userText)
        val assistantId = UUID.randomUUID().toString()
        val loadingMessage = Message(id = assistantId, role = MessageRole.ASSISTANT, content = "", isLoading = true)

        val persisted = baseConversation.copy(
            title = title,
            modelId = _currentModel.value,
            messages = baseConversation.messages + userMessage
        )
        persistConversation(persisted)
        persistInMemory(persisted.copy(messages = persisted.messages + loadingMessage))

        _error.value = null
        _isLoading.value = true
        currentRequestJob?.cancel()
        streamingConversationId = persisted.id

        // Priority: bridge (if online) → embedded agentic loop (streaming + tools, falls back to plain chat on 400)
        if (_bridgeActive.value) {
            sendViaBridge(persisted, assistantId)
        } else {
            sendViaEmbeddedLoop(persisted, assistantId)
        }
    }

    // ─── Embedded agentic loop (built-in, no bridge needed) ──────────────────

    private fun sendViaEmbeddedLoop(conversation: Conversation, assistantId: String) {
        currentRequestJob = viewModelScope.launch {
            val history = conversation.messages.map { msg ->
                (if (msg.role == MessageRole.USER) "user" else "assistant") to msg.content
            }
            var fullText = ""
            val toolEvents = mutableListOf<ToolEvent>()
            var failed = false

            api.streamAgenticLoop(
                token = prefs.githubToken,
                model = _currentModel.value,
                systemPrompt = prefs.systemPrompt,
                messages = history
            ).catch { throwable ->
                if (throwable is CancellationException) throw throwable
                failed = true
                val msg = throwable.message ?: "Unknown error"
                _error.value = msg
                finalizeMessage(conversation, assistantId, "Error: $msg", emptyList())
                _isLoading.value = false
                streamingConversationId = null
            }.collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> {
                        fullText += event.text
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.ToolCall -> {
                        toolEvents.add(ToolEvent("call", event.name, event.args))
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.ToolResult -> {
                        toolEvents.add(ToolEvent("result", event.name, event.output, event.isError))
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.Error -> {
                        failed = true
                        _error.value = event.message
                        finalizeMessage(conversation, assistantId, "Error: ${event.message}", toolEvents)
                        _isLoading.value = false
                        streamingConversationId = null
                    }
                    StreamEvent.Done -> { /* handled below */ }
                }
            }

            if (!failed) {
                finalizeMessage(conversation, assistantId, fullText, toolEvents)
                _isLoading.value = false
                streamingConversationId = null
            }
        }
    }

    // ─── Direct streaming (default, no tools) ────────────────────────────────

    private fun sendDirect(conversation: Conversation, assistantId: String) {
        currentRequestJob = viewModelScope.launch {
            val history = conversation.messages.map { msg ->
                (if (msg.role == MessageRole.USER) "user" else "assistant") to msg.content
            }
            var fullText = ""
            var failed = false

            api.streamMessage(
                token = prefs.githubToken,
                model = _currentModel.value,
                systemPrompt = prefs.systemPrompt,
                messages = history
            ).catch { throwable ->
                if (throwable is CancellationException) throw throwable
                failed = true
                val msg = throwable.message ?: "Unknown error"
                _error.value = msg
                finalizeMessage(conversation, assistantId, "Error: $msg", emptyList())
                _isLoading.value = false
                streamingConversationId = null
            }.collect { chunk ->
                fullText += chunk
                updateLoadingMessage(conversation, assistantId, fullText, emptyList())
            }

            if (!failed) {
                finalizeMessage(conversation, assistantId, fullText, emptyList())
                _isLoading.value = false
                streamingConversationId = null
            }
        }
    }

    // ─── Bridge passthrough (optional) ───────────────────────────────────────

    private fun sendViaBridge(conversation: Conversation, assistantId: String) {
        currentRequestJob = viewModelScope.launch {
            val history = conversation.messages.map { msg ->
                (if (msg.role == MessageRole.USER) "user" else "assistant") to msg.content
            }
            var fullText = ""
            val toolEvents = mutableListOf<ToolEvent>()
            var failed = false

            api.streamBridge(
                bridgeUrl = prefs.bridgeUrl,
                model = _currentModel.value,
                systemPrompt = prefs.systemPrompt,
                messages = history,
                fallbackToken = prefs.githubToken
            ).catch { throwable ->
                if (throwable is CancellationException) throw throwable
                failed = true
                // Bridge went down — fall back to embedded loop
                _bridgeActive.value = false
                sendViaEmbeddedLoop(conversation, assistantId)
            }.collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> {
                        fullText += event.text
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.ToolCall -> {
                        toolEvents.add(ToolEvent("call", event.name, event.args))
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.ToolResult -> {
                        toolEvents.add(ToolEvent("result", event.name, event.output, event.isError))
                        updateLoadingMessage(conversation, assistantId, fullText, toolEvents)
                    }
                    is StreamEvent.Error -> {
                        failed = true
                        _error.value = event.message
                        finalizeMessage(conversation, assistantId, "Error: ${event.message}", toolEvents)
                        _isLoading.value = false
                        streamingConversationId = null
                    }
                    StreamEvent.Done -> { /* handled below */ }
                }
            }

            if (!failed) {
                finalizeMessage(conversation, assistantId, fullText, toolEvents)
                _isLoading.value = false
                streamingConversationId = null
            }
        }
    }

    // ─── Slash commands ───────────────────────────────────────────────────────

    private fun handleSlashCommand(command: String) {
        val parts = command.removePrefix("/").split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""

        val responseText = when (cmd) {
            "help" -> buildString {
                appendLine("**Available slash commands:**")
                appendLine()
                appendLine("• `/help` — show this help")
                appendLine("• `/clear` — clear current conversation")
                appendLine("• `/new` — start a new conversation")
                appendLine("• `/model [id]` — switch model (e.g. `/model gpt-4o`)")
                appendLine("• `/system [prompt]` — set system prompt")
                appendLine("• `/bridge` — check optional bridge server status")
                appendLine()
                if (_bridgeActive.value) appendLine("⚡ **Bridge connected** — agentic tools active (UserLAnd)")
                else appendLine("💬 **Direct mode** — plain streaming chat")
            }
            "clear" -> { clearActiveMessages(); return }
            "new"   -> { newConversation(); return }
            "model" -> {
                if (arg.isBlank()) {
                    val models = supportedModels.joinToString("\n") { (id, label) ->
                        val mark = if (id == _currentModel.value) "✓ " else "  "
                        "$mark`$id` — $label"
                    }
                    "**Available models:**\n\n$models"
                } else {
                    val matched = supportedModels.firstOrNull { (id, _) -> id == arg }
                    if (matched != null) { setModel(arg); "Switched to **${matched.second}**" }
                    else "Unknown model: `$arg`. Use `/model` to list available models."
                }
            }
            "system" -> {
                if (arg.isBlank()) "Current system prompt:\n\n> ${prefs.systemPrompt}"
                else { prefs.systemPrompt = arg; "System prompt updated." }
            }
            "bridge" -> {
                viewModelScope.launch {
                    val available = api.isBridgeAvailable(prefs.bridgeUrl)
                    _bridgeActive.value = available
                    addSystemMessage(if (available)
                        "⚡ **Bridge online** at ${prefs.bridgeUrl}\n\nRequests will route through bridge (UserLAnd environment)."
                    else
                        "🔴 **Bridge offline** — using embedded tools (Termux environment).\n\nTo start: `python3 ~/bridge.py --token TOKEN`"
                    )
                }
                return
            }
            else -> "Unknown command: `/$cmd`. Type `/help` for available commands."
        }
        addSystemMessage(responseText)
    }

    private fun addSystemMessage(content: String) {
        val conversation = _activeConversation.value ?: Conversation(modelId = _currentModel.value)
        persistConversation(conversation.copy(messages = conversation.messages + Message(role = MessageRole.ASSISTANT, content = content)))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun updateLoadingMessage(base: Conversation, assistantId: String, content: String, toolEvents: List<ToolEvent>) {
        persistInMemory(base.copy(messages = base.messages + Message(
            id = assistantId, role = MessageRole.ASSISTANT, content = content,
            isLoading = false, toolEvents = toolEvents.toList()
        )))
    }

    private fun finalizeMessage(base: Conversation, assistantId: String, content: String, toolEvents: List<ToolEvent>) {
        persistConversation(base.copy(messages = base.messages + Message(
            id = assistantId, role = MessageRole.ASSISTANT, content = content,
            toolEvents = toolEvents.toList()
        )), activate = _activeConversation.value?.id == base.id)
    }

    fun newConversation() {
        persistConversation(Conversation(modelId = _currentModel.value))
        _error.value = null
    }

    fun switchConversation(id: String) {
        _conversations.value.firstOrNull { it.id == id }?.let { conversation ->
            val model = resolveModel(conversation.modelId)
            prefs.selectedModel = model
            setActiveConversation(conversation.copy(modelId = model))
        }
    }

    fun deleteConversation(id: String) {
        if (streamingConversationId == id) cancelStreaming()
        repository.delete(id)
        val updated = _conversations.value.filterNot { it.id == id }.sortedByDescending { it.createdAt }
        _conversations.value = updated
        if (_activeConversation.value?.id == id) {
            val next = updated.firstOrNull()
            if (next != null) { prefs.selectedModel = next.modelId; setActiveConversation(next) }
            else { _activeConversation.value = null; _messages.value = emptyList(); _currentModel.value = prefs.selectedModel }
        }
    }

    fun setModel(modelId: String) {
        if (prefs.supportedModels.none { it.first == modelId }) return
        prefs.selectedModel = modelId
        _currentModel.value = modelId
        _activeConversation.value?.let { persistConversation(it.copy(modelId = modelId)) }
    }

    fun clearActiveMessages() {
        _activeConversation.value?.let { conversation ->
            if (streamingConversationId == conversation.id) cancelStreaming()
            persistConversation(conversation.copy(title = "New Chat", messages = emptyList()))
        }
    }

    fun dismissError() { _error.value = null }

    fun regenerateLastResponse() {
        if (_isLoading.value) return
        val msgs = _messages.value
        val lastAssistantIdx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx < 0) return
        val conversation = _activeConversation.value ?: return
        val trimmed = conversation.copy(messages = msgs.take(lastAssistantIdx))
        persistConversation(trimmed)
        _error.value = null
        _isLoading.value = true
        val assistantId = UUID.randomUUID().toString()
        persistInMemory(trimmed.copy(messages = trimmed.messages + Message(id = assistantId, role = MessageRole.ASSISTANT, content = "", isLoading = true)))
        currentRequestJob?.cancel()
        streamingConversationId = trimmed.id
        if (_bridgeActive.value) sendViaBridge(trimmed, assistantId) else sendViaEmbeddedLoop(trimmed, assistantId)
    }

    fun getShareText(): String {
        val conv = _activeConversation.value ?: return ""
        return buildString {
            appendLine("# ${conv.title}")
            appendLine("Model: ${conv.modelId}")
            appendLine()
            _messages.value.forEach { msg ->
                appendLine("**${if (msg.role == MessageRole.USER) "You" else "Copilot"}:**")
                appendLine(msg.content)
                appendLine()
            }
        }
    }

    private fun persistInMemory(conversation: Conversation) {
        val stored = conversation.copy(messages = conversation.messages.map { it.copy(isLoading = false) })
        _conversations.value = _conversations.value.filterNot { it.id == conversation.id }
            .plus(stored).sortedByDescending { it.createdAt }
        if (_activeConversation.value?.id == conversation.id) setActiveConversation(conversation)
    }

    private fun setActiveConversation(conversation: Conversation) {
        _activeConversation.value = conversation
        _messages.value = conversation.messages
        _currentModel.value = resolveModel(conversation.modelId)
    }

    private fun persistConversation(conversation: Conversation, activate: Boolean = true) {
        val persisted = conversation.copy(messages = conversation.messages.map { it.copy(isLoading = false) })
        repository.save(persisted)
        persistInMemory(persisted)
        if (activate) setActiveConversation(persisted)
    }

    fun cancelStreaming() {
        currentRequestJob?.cancel()
        currentRequestJob = null
        streamingConversationId = null
        _isLoading.value = false
    }

    private fun resolveModel(modelId: String): String {
        val valid = prefs.supportedModels.map { it.first }
        return if (modelId in valid) modelId else (valid.firstOrNull() ?: CopilotApiClient.DEFAULT_MODEL)
    }
}
