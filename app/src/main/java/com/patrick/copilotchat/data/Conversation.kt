package com.patrick.copilotchat.data

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<Message> = emptyList(),
    val modelId: String = "claude-sonnet-4.6",
    val createdAt: Long = System.currentTimeMillis()
)
