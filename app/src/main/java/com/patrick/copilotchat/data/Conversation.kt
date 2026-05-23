package com.patrick.copilotchat.data

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<Message> = emptyList(),
    val modelId: String = "gpt-4o",
    val createdAt: Long = System.currentTimeMillis()
)
