package com.maple.agent

import com.maple.llm.ChatMessage

/**
 * 每个 bot 独立的多轮对话记忆。
 */
class ConversationMemory(private val maxTurns: Int) {

    private val history = mutableListOf<ChatMessage>()

    fun addUserMessage(content: String) {
        history.add(ChatMessage("user", content))
        trimHistory()
    }

    fun addAssistantMessage(content: String) {
        history.add(ChatMessage("assistant", content))
        trimHistory()
    }

    fun getMessages(): List<ChatMessage> = history.toList()

    fun clear() {
        history.clear()
    }

    private fun trimHistory() {
        // 每轮 = 1 user + 1 assistant，保留最近 maxTurns 轮
        val maxMessages = maxTurns * 2
        while (history.size > maxMessages) {
            history.removeFirst()
        }
    }
}
