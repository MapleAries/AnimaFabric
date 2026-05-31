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

class LLMClient(private val config: MCMindConfig) {

    private val logger = LoggerFactory.getLogger("mc-mind")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeout))
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 流式请求：通过回调逐 chunk 返回内容
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            val request = ChatRequest(
                model = config.model,
                messages = messages,
                maxTokens = config.maxTokens,
                stream = true
            )

            val requestBody = json.encodeToString(request)
            logger.info("[MC-Mind] 发送 LLM 请求: URL={}, Model={}", config.apiUrl, config.model)
            logger.debug("[MC-Mind] 请求体: {}", requestBody)

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
                return@withContext ""
            }

            val fullContent = StringBuilder()
            var lineCount = 0
            response.body().forEach { line ->
                lineCount++
                logger.debug("[MC-Mind] 收到行 {}: {}", lineCount, line)

                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        logger.debug("[MC-Mind] 收到 [DONE] 标记")
                        return@forEach
                    }
                    try {
                        val chunk = json.decodeFromString<StreamChunk>(data)
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (content != null) {
                            fullContent.append(content)
                            onChunk(content)
                            logger.debug("[MC-Mind] 收到内容: {}", content)
                        }
                    } catch (e: Exception) {
                        logger.warn("[MC-Mind] 解析 chunk 失败: {} - 原始数据: {}", e.message, data)
                    }
                } else if (line.isNotBlank()) {
                    logger.warn("[MC-Mind] 非预期行: {}", line)
                }
            }

            logger.info("[MC-Mind] LLM 响应完成，共 {} 行，内容长度: {}", lineCount, fullContent.length)
            fullContent.toString()
        } catch (e: Exception) {
            logger.error("[MC-Mind] LLM 请求异常: {}", e.message, e)
            ""
        }
    }
}
