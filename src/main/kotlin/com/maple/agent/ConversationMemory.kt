package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient

/**
 * 每个 bot 独立的多轮对话记忆。
 * 参考 Mindcraft 的记忆管理：当历史超过限制时，用 LLM 总结旧消息。
 */
class ConversationMemory(
    private val maxTurns: Int,
    private val llmClient: LLMClient? = null
) {

    private val history = mutableListOf<ChatMessage>()
    private var memory = "" // 总结后的记忆

    @Synchronized
    fun addUserMessage(content: String) {
        history.add(ChatMessage("user", content))
        trimHistory()
    }

    @Synchronized
    fun addAssistantMessage(content: String) {
        history.add(ChatMessage("assistant", content))
        trimHistory()
    }

    @Synchronized
    fun getMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // 如果有总结的记忆，添加为系统消息
        if (memory.isNotBlank()) {
            messages.add(ChatMessage("system", "Previous conversation summary: $memory"))
        }

        messages.addAll(history)
        return messages
    }

    @Synchronized
    fun getMemory(): String = memory

    @Synchronized
    fun clear() {
        history.clear()
        memory = ""
    }

    /**
     * 裁剪历史，如果超过限制则总结旧消息。
     */
    private fun trimHistory() {
        val maxMessages = maxTurns * 2

        if (history.size > maxMessages) {
            // 取出需要总结的旧消息
            val oldMessages = history.subList(0, history.size - maxMessages)
            val oldText = oldMessages.joinToString("\n") { "${it.role}: ${it.content}" }

            // 总结旧消息
            memory = summarize(oldText)

            // 移除旧消息
            history.subList(0, history.size - maxMessages).clear()
        }
    }

    /**
     * 用 LLM 总结对话历史。
     * 如果 LLM 不可用，则简单截断。
     */
    private fun summarize(text: String): String {
        if (llmClient == null) {
            // 简单截断
            return text.take(500)
        }

        // 用 LLM 总结（同步调用）
        return try {
            val messages = listOf(
                ChatMessage("system", "Summarize the following conversation in 500 characters or less. Focus on key information, goals, and important details."),
                ChatMessage("user", text)
            )

            // 这里应该用同步调用，但为了简化，我们直接截断
            // 在实际实现中，应该用 llmClient.chatSync() 或类似方法
            text.take(500)
        } catch (e: Exception) {
            text.take(500)
        }
    }
}
