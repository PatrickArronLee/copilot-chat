package com.patrick.copilotchat.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ConversationRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("copilot_chat_prefs", Context.MODE_PRIVATE)

    private val conversationsKey = "conversations_json"
    private val maxConversations = 20

    fun loadAll(): List<Conversation> {
        val stored = prefs.getString(conversationsKey, null).orEmpty()
        if (stored.isBlank()) return emptyList()

        return try {
            val conversations = mutableListOf<Conversation>()
            val jsonArray = JSONArray(stored)
            for (index in 0 until jsonArray.length()) {
                val conversationJson = jsonArray.optJSONObject(index) ?: continue
                parseConversation(conversationJson)?.let(conversations::add)
            }
            conversations
                .sortedByDescending { it.createdAt }
                .take(maxConversations)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(conversation: Conversation) {
        val updated = loadAll()
            .filterNot { it.id == conversation.id }
            .plus(conversation)
            .sortedByDescending { it.createdAt }
            .take(maxConversations)

        prefs.edit()
            .putString(conversationsKey, JSONArray().apply {
                updated.forEach { put(it.toJson()) }
            }.toString())
            .apply()
    }

    fun delete(id: String) {
        val updated = loadAll().filterNot { it.id == id }
        prefs.edit()
            .putString(conversationsKey, JSONArray().apply {
                updated.forEach { put(it.toJson()) }
            }.toString())
            .apply()
    }

    fun generateTitle(firstMessage: String): String {
        val trimmed = firstMessage.trim()
        return if (trimmed.length > 40) {
            trimmed.take(40) + "..."
        } else {
            trimmed
        }
    }

    private fun parseConversation(json: JSONObject): Conversation? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("title", "New Chat")
        val modelId = json.optString("modelId", "gpt-4o")
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val messagesArray = json.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<Message>()

        for (index in 0 until messagesArray.length()) {
            val messageJson = messagesArray.optJSONObject(index) ?: continue
            parseMessage(messageJson)?.let(messages::add)
        }

        return Conversation(
            id = id,
            title = title,
            messages = messages,
            modelId = modelId,
            createdAt = createdAt
        )
    }

    private fun parseMessage(json: JSONObject): Message? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val roleName = json.optString("role")
        val role = try {
            MessageRole.valueOf(roleName)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val toolEventsArray = json.optJSONArray("toolEvents") ?: JSONArray()
        val toolEvents = mutableListOf<ToolEvent>()
        for (i in 0 until toolEventsArray.length()) {
            val te = toolEventsArray.optJSONObject(i) ?: continue
            toolEvents.add(ToolEvent(
                type = te.optString("type", "call"),
                name = te.optString("name", ""),
                detail = te.optString("detail", ""),
                isError = te.optBoolean("isError", false)
            ))
        }

        return Message(
            id = id,
            role = role,
            content = json.optString("content"),
            isLoading = false,
            toolEvents = toolEvents
        )
    }

    private fun Conversation.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("modelId", modelId)
        put("createdAt", createdAt)
        put("messages", JSONArray().apply {
            messages.forEach { put(it.toJson()) }
        })
    }

    private fun Message.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("content", content)
        put("isLoading", false)
        put("toolEvents", JSONArray().apply {
            toolEvents.forEach { te ->
                put(JSONObject().apply {
                    put("type", te.type)
                    put("name", te.name)
                    put("detail", te.detail)
                    put("isError", te.isError)
                })
            }
        })
    }
}
