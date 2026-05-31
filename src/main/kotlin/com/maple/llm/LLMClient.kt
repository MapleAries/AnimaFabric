package com.maple.llm

import com.maple.config.MCMindConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LLMClient(private val config: MCMindConfig) {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeout))
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 非流式请求：返回完整响应
     */
    suspend fun chat(messages: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        try {
            val request = ChatRequest(
                model = config.model,
                messages = messages,
                maxTokens = config.maxTokens,
                stream = false
            )

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
                .timeout(Duration.ofSeconds(config.timeout))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                return@withContext null
            }

            val chatResponse = json.decodeFromString<ChatResponse>(response.body())
            chatResponse.choices.firstOrNull()?.message?.content
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
                .timeout(Duration.ofSeconds(config.timeout * 2)) // 流式超时更长
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

            val fullContent = StringBuilder()
            response.body().forEachLine { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") return@forEachLine
                    try {
                        val chunk = json.decodeFromString<StreamChunk>(data)
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (content != null) {
                            fullContent.append(content)
                            onChunk(content)
                        }
                    } catch (_: Exception) {
                        // 忽略解析错误的 chunk
                    }
                }
            }

            fullContent.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 检查 API 是否可达
     */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl.replace("/chat/completions", "/models")))
                .header("Authorization", "Bearer ${config.apiKey}")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
}
