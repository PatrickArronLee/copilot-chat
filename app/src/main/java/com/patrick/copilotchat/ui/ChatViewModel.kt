package com.patrick.copilotchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.data.AppPreferences
import com.patrick.copilotchat.data.Message
import com.patrick.copilotchat.data.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(private val prefs: AppPreferences) : ViewModel() {

    private val api = CopilotApiClient()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isLoading.value) return
        if (!prefs.isConfigured) {
            _error.value = "Please add your GitHub token in Settings"
            return
        }

        val userMessage = Message(role = MessageRole.USER, content = userText.trim())
        _messages.update { it + userMessage }
        _error.value = null

        val assistantId = UUID.randomUUID().toString()
        val loadingMessage = Message(id = assistantId, role = MessageRole.ASSISTANT, content = "", isLoading = true)
        _messages.update { it + loadingMessage }
        _isLoading.value = true

        viewModelScope.launch {
            val history = _messages.value
                .filter { !it.isLoading }
                .map { msg ->
                    (if (msg.role == MessageRole.USER) "user" else "assistant") to msg.content
                }

            var fullResponse = ""

            api.streamMessage(
                token = prefs.githubToken,
                model = prefs.selectedModel,
                systemPrompt = prefs.systemPrompt,
                messages = history
            ).catch { e ->
                _messages.update { msgs ->
                    msgs.map { if (it.id == assistantId) it.copy(content = "Error: ${e.message}", isLoading = false) else it }
                }
                _error.value = e.message
                _isLoading.value = false
            }.collect { chunk ->
                fullResponse += chunk
                _messages.update { msgs ->
                    msgs.map {
                        if (it.id == assistantId) it.copy(content = fullResponse, isLoading = false)
                        else it
                    }
                }
            }

            _isLoading.value = false
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun dismissError() {
        _error.value = null
    }
}
