package com.patrick.copilotchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.data.AppPreferences
import com.patrick.copilotchat.data.Conversation
import com.patrick.copilotchat.data.ConversationRepository
import com.patrick.copilotchat.data.Message
import com.patrick.copilotchat.data.MessageRole
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

    private val _currentModel = MutableStateFlow(_activeConversation.value?.modelId ?: prefs.selectedModel)
    val currentModel: StateFlow<String> = _currentModel

    private var currentRequestJob: Job? = null
    private var streamingConversationId: String? = null

    fun sendMessage(text: String) {
        val userText = text.trim()
        if (userText.isBlank() || _isLoading.value) return
        if (!prefs.isConfigured) {
            _error.value = "Please add your GitHub token in Settings"
            return
        }

        val baseConversation = _activeConversation.value ?: Conversation(modelId = _currentModel.value)
        val title = if (baseConversation.title == "New Chat" || baseConversation.title.isBlank()) {
            repository.generateTitle(userText)
        } else {
            baseConversation.title
        }
        val userMessage = Message(role = MessageRole.USER, content = userText)
        val assistantId = UUID.randomUUID().toString()
        val loadingMessage = Message(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = "",
            isLoading = true
        )

        val persistedConversation = baseConversation.copy(
            title = title,
            modelId = _currentModel.value,
            messages = baseConversation.messages + userMessage
        )
        persistConversation(persistedConversation)
        persistInMemory(persistedConversation.copy(messages = persistedConversation.messages + loadingMessage))

        _error.value = null
        _isLoading.value = true

        currentRequestJob?.cancel()
        streamingConversationId = persistedConversation.id
        currentRequestJob = viewModelScope.launch {
            val history = persistedConversation.messages.map { message ->
                (if (message.role == MessageRole.USER) "user" else "assistant") to message.content
            }

            var fullResponse = ""
            var failed = false

            api.streamMessage(
                token = prefs.githubToken,
                model = _currentModel.value,
                systemPrompt = prefs.systemPrompt,
                messages = history
            ).catch { throwable ->
                if (throwable is CancellationException) throw throwable
                failed = true
                val errorMessage = throwable.message ?: "Unknown error"
                _error.value = errorMessage
                persistConversation(
                    persistedConversation.copy(
                        messages = persistedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = "Error: $errorMessage"
                        )
                    ),
                    activate = _activeConversation.value?.id == persistedConversation.id
                )
                _isLoading.value = false
                streamingConversationId = null
            }.collect { chunk ->
                fullResponse += chunk
                persistInMemory(
                    persistedConversation.copy(
                        messages = persistedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = fullResponse,
                            isLoading = false
                        )
                    )
                )
            }

            if (!failed) {
                persistConversation(
                    persistedConversation.copy(
                        messages = persistedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = fullResponse
                        )
                    ),
                    activate = _activeConversation.value?.id == persistedConversation.id
                )
                _isLoading.value = false
                streamingConversationId = null
            }
        }
    }

    fun newConversation() {
        val conversation = Conversation(modelId = _currentModel.value)
        persistConversation(conversation)
        _error.value = null
    }

    fun switchConversation(id: String) {
        _conversations.value.firstOrNull { it.id == id }?.let { conversation ->
            prefs.selectedModel = conversation.modelId
            setActiveConversation(conversation)
        }
    }

    fun deleteConversation(id: String) {
        if (streamingConversationId == id) {
            cancelStreaming()
        }

        repository.delete(id)
        val updated = _conversations.value.filterNot { it.id == id }.sortedByDescending { it.createdAt }
        _conversations.value = updated

        if (_activeConversation.value?.id == id) {
            val nextConversation = updated.firstOrNull()
            if (nextConversation != null) {
                prefs.selectedModel = nextConversation.modelId
                setActiveConversation(nextConversation)
            } else {
                _activeConversation.value = null
                _messages.value = emptyList()
                _currentModel.value = prefs.selectedModel
            }
        }
    }

    fun setModel(modelId: String) {
        if (CopilotApiClient.AVAILABLE_MODELS.none { it.first == modelId }) return
        prefs.selectedModel = modelId
        _currentModel.value = modelId

        _activeConversation.value?.let { conversation ->
            persistConversation(conversation.copy(modelId = modelId))
        }
    }

    fun clearActiveMessages() {
        _activeConversation.value?.let { conversation ->
            if (streamingConversationId == conversation.id) {
                cancelStreaming()
            }
            persistConversation(conversation.copy(title = "New Chat", messages = emptyList()))
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun regenerateLastResponse() {
        if (_isLoading.value) return
        val msgs = _messages.value
        val lastAssistantIdx = msgs.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx < 0) return
        msgs.take(lastAssistantIdx).lastOrNull { it.role == MessageRole.USER } ?: return
        val conversation = _activeConversation.value ?: return
        val trimmedMessages = msgs.take(lastAssistantIdx)
        val trimmedConversation = conversation.copy(messages = trimmedMessages)
        persistConversation(trimmedConversation)

        _error.value = null
        _isLoading.value = true
        val assistantId = UUID.randomUUID().toString()
        val loadingMessage = Message(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = "",
            isLoading = true
        )
        persistInMemory(trimmedConversation.copy(messages = trimmedConversation.messages + loadingMessage))

        currentRequestJob?.cancel()
        streamingConversationId = trimmedConversation.id
        currentRequestJob = viewModelScope.launch {
            val history = trimmedConversation.messages.map { message ->
                (if (message.role == MessageRole.USER) "user" else "assistant") to message.content
            }

            var fullResponse = ""
            var failed = false

            api.streamMessage(
                token = prefs.githubToken,
                model = _currentModel.value,
                systemPrompt = prefs.systemPrompt,
                messages = history
            ).catch { throwable ->
                if (throwable is CancellationException) throw throwable
                failed = true
                val errorMessage = throwable.message ?: "Unknown error"
                _error.value = errorMessage
                persistConversation(
                    trimmedConversation.copy(
                        messages = trimmedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = "Error: $errorMessage"
                        )
                    ),
                    activate = _activeConversation.value?.id == trimmedConversation.id
                )
                _isLoading.value = false
                streamingConversationId = null
            }.collect { chunk ->
                fullResponse += chunk
                persistInMemory(
                    trimmedConversation.copy(
                        messages = trimmedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = fullResponse,
                            isLoading = false
                        )
                    )
                )
            }

            if (!failed) {
                persistConversation(
                    trimmedConversation.copy(
                        messages = trimmedConversation.messages + Message(
                            id = assistantId,
                            role = MessageRole.ASSISTANT,
                            content = fullResponse
                        )
                    ),
                    activate = _activeConversation.value?.id == trimmedConversation.id
                )
                _isLoading.value = false
                streamingConversationId = null
            }
        }
    }

    fun getShareText(): String {
        val conv = _activeConversation.value ?: return ""
        val sb = StringBuilder()
        sb.appendLine("# ${conv.title}")
        sb.appendLine("Model: ${conv.modelId}")
        sb.appendLine()
        _messages.value.forEach { msg ->
            val role = if (msg.role == MessageRole.USER) "You" else "Copilot"
            sb.appendLine("**$role:**")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun persistInMemory(conversation: Conversation) {
        val storedConversation = conversation.copy(
            messages = conversation.messages.map { it.copy(isLoading = false) }
        )
        _conversations.value = _conversations.value
            .filterNot { it.id == conversation.id }
            .plus(storedConversation)
            .sortedByDescending { it.createdAt }

        if (_activeConversation.value?.id == conversation.id) {
            setActiveConversation(conversation)
        }
    }

    private fun setActiveConversation(conversation: Conversation) {
        _activeConversation.value = conversation
        _messages.value = conversation.messages
        _currentModel.value = conversation.modelId
    }

    private fun persistConversation(conversation: Conversation, activate: Boolean = true) {
        val persisted = conversation.copy(messages = conversation.messages.map { it.copy(isLoading = false) })
        repository.save(persisted)
        persistInMemory(persisted)
        if (activate) {
            setActiveConversation(persisted)
        }
    }

    fun cancelStreaming() {
        currentRequestJob?.cancel()
        currentRequestJob = null
        streamingConversationId = null
        _isLoading.value = false
    }
}
