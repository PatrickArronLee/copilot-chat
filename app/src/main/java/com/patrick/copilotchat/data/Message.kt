package com.patrick.copilotchat.data

import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

/** A single tool call or result attached to an assistant message. */
data class ToolEvent(
    val type: String,      // "call" or "result"
    val name: String,
    val detail: String,    // args (JSON) for call, output text for result
    val isError: Boolean = false
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val isLoading: Boolean = false,
    val toolEvents: List<ToolEvent> = emptyList()
)
