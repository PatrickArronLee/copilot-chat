package com.patrick.copilotchat.data

import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val isLoading: Boolean = false
)
