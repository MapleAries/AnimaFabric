package com.maple.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// === 请求模型 ===

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false,
    val temperature: Double = 0.7
)

@Serializable
data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String
)

// === 响应模型（非流式）===

@Serializable
data class ChatResponse(
    val id: String = "",
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage = ChatMessage("", ""),
    @SerialName("finish_reason") val finishReason: String = ""
)

// === 流式响应模型 ===

@Serializable
data class StreamChunk(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)
