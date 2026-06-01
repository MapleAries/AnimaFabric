package com.maple.llm

import com.maple.config.MCMindConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * LLM 响应结果，包含思考内容和实际输出。
 */
data class LLMResponse(
    val thinking: String,  // 思考过程（reasoning_content）
    val content: String    // 实际输出（content）
)

class LLMClient(private val config: MCMindConfig) {

    private val logger = LoggerFactory.getLogger("mc-mind")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeout))
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 流式请求：返回思考内容和实际输出。
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        onThinking: (String) -> Unit = {},
        onContent: (String) -> Unit = {}
    ): LLMResponse = withContext(Dispatchers.IO) {
        try {
            val request = ChatRequest(
                model = config.model,
                messages = messages,
                maxTokens = config.maxTokens,
                stream = true
            )

            val requestBody = json.encodeToString(request)
            logger.info("[MC-Mind] 发送 LLM 请求: URL={}, Model={}", config.apiUrl, config.model)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(config.timeout * 2))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

            logger.info("[MC-Mind] LLM 响应状态码: {}", response.statusCode())

            if (response.statusCode() != 200) {
                val errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"))
                logger.error("[MC-Mind] LLM 请求失败: 状态码={}, 响应={}", response.statusCode(), errorBody)
                return@withContext LLMResponse("", "")
            }

            val thinkingBuilder = StringBuilder()
            val contentBuilder = StringBuilder()

            response.body().forEach { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") return@forEach
                    try {
                        val chunk = json.decodeFromString<StreamChunk>(data)
                        val delta = chunk.choices.firstOrNull()?.delta
                        if (delta != null) {
                            // 分别收集思考内容和实际输出
                            if (delta.reasoningContent != null && delta.reasoningContent.isNotEmpty()) {
                                thinkingBuilder.append(delta.reasoningContent)
                                onThinking(delta.reasoningContent)
                            }
                            if (delta.content != null && delta.content.isNotEmpty()) {
                                contentBuilder.append(delta.content)
                                onContent(delta.content)
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误
                    }
                }
            }

            logger.info("[MC-Mind] LLM 响应完成 - 思考: {}字, 内容: {}字", thinkingBuilder.length, contentBuilder.length)

            // 如果 content 为空，尝试从 reasoning_content 中提取 JSON
            val finalContent = if (contentBuilder.isBlank() && thinkingBuilder.isNotBlank()) {
                extractJsonFromThinking(thinkingBuilder.toString())
            } else {
                contentBuilder.toString()
            }

            LLMResponse(thinkingBuilder.toString(), finalContent)
        } catch (e: Exception) {
            logger.error("[MC-Mind] LLM 请求异常: {}", e.message, e)
            LLMResponse("", "")
        }
    }

    /**
     * 从思考内容中提取 JSON。
     * 有些模型只返回 reasoning_content，需要从中提取 JSON。
     */
    private fun extractJsonFromThinking(thinking: String): String {
        // 尝试找到最后一个 JSON 对象
        val lastJsonStart = thinking.lastIndexOf('{')
        val lastJsonEnd = thinking.lastIndexOf('}')

        if (lastJsonStart != -1 && lastJsonEnd != -1 && lastJsonStart < lastJsonEnd) {
            val jsonStr = thinking.substring(lastJsonStart, lastJsonEnd + 1)
            logger.info("[MC-Mind] 从思考内容中提取 JSON: {}", jsonStr.take(100))
            return jsonStr
        }

        return ""
    }
}
