package com.maple.llm

import com.maple.config.AnimaFabricConfig
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

class LLMClient(private val config: AnimaFabricConfig) {

    private val logger = LoggerFactory.getLogger("anima-fabric")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeout))
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        // 思考内容正则（匹配 <think>...</think> 块）
        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL)
    }

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
            logger.info("[AnimaFabric] 发送 LLM 请求: URL={}, Model={}", config.apiUrl, config.model)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(config.timeout * 2))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

            logger.info("[AnimaFabric] LLM 响应状态码: {}", response.statusCode())

            if (response.statusCode() != 200) {
                val errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"))
                logger.error("[AnimaFabric] LLM 请求失败: 状态码={}, 响应={}", response.statusCode(), errorBody)
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

            logger.info("[AnimaFabric] LLM 响应完成 - 思考: {}字, 内容: {}字", thinkingBuilder.length, contentBuilder.length)

            // 处理响应内容
            val finalContent = processResponse(thinkingBuilder.toString(), contentBuilder.toString())

            LLMResponse(thinkingBuilder.toString(), finalContent)
        } catch (e: Exception) {
            logger.error("[AnimaFabric] LLM 请求异常: {}", e.message, e)
            LLMResponse("", "")
        }
    }

    /**
     * 处理响应内容，提取有效的 JSON。
     * 参考 AI-Player 的处理方式：
     * 1. 剥离思考内容（<think>...</think> 块）
     * 2. 从 content 中提取 JSON
     * 3. 如果 content 为空，从 reasoning_content 中提取
     */
    private fun processResponse(thinking: String, content: String): String {
        // 如果有实际内容，先尝试从中提取 JSON
        if (content.isNotBlank()) {
            val result = extractJson(content)
            if (result.isNotBlank()) return result
        }

        // content 中没有有效 JSON，尝试从思考内容中提取
        if (thinking.isNotBlank()) {
            logger.info("[AnimaFabric] 从思考内容中提取 JSON（content 无有效 JSON）")
            val result = extractJsonFromThinking(thinking)
            if (result.isNotBlank()) return result
        }

        // 最后兜底：content 中有命令格式（!tool(...)），直接返回
        if (content.isNotBlank() && content.contains("!")) {
            logger.info("[AnimaFabric] 返回包含命令的原始内容")
            return content
        }

        return ""
    }

    /**
     * 从文本中提取 JSON。
     * 参考 AI-Player 和 superwy 的方式：找第一个 { 到最后一个 }。
     */
    private fun extractJson(text: String): String {
        // 1. 剥离思考内容（如果有）
        val cleaned = THINK_BLOCK_REGEX.replace(text, "").trim()

        // 2. 找第一个 { 和最后一个 }
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')

        if (firstBrace == -1 || lastBrace == -1 || firstBrace >= lastBrace) {
            logger.warn("[AnimaFabric] 未找到有效的 JSON")
            return ""
        }

        val jsonStr = cleaned.substring(firstBrace, lastBrace + 1)
        logger.info("[AnimaFabric] 提取的 JSON: {}", jsonStr.take(200))
        return jsonStr
    }

    /**
     * 从思考内容中提取 JSON。
     * 某些模型（如 deepseek-v4-flash）只返回 reasoning_content。
     * 尝试多种方式提取 JSON。
     */
    private fun extractJsonFromThinking(thinking: String): String {
        // 方式1：找命令格式 !toolName(param1, param2)
        val commandPattern = Regex("""!(\w+)\([^)]*\)""")
        val commandMatches = commandPattern.findAll(thinking).toList()
        if (commandMatches.isNotEmpty()) {
            val result = commandMatches.joinToString(" ") { it.value }
            logger.info("[AnimaFabric] 从思考内容提取的命令: {}", result)
            return result
        }

        // 方式2：找最后一个完整的 JSON 对象（包含 pipeline 或 tool 或 action）
        val lastBrace = thinking.lastIndexOf('}')
        if (lastBrace != -1) {
            var braceCount = 0
            var start = lastBrace
            for (i in lastBrace downTo 0) {
                when (thinking[i]) {
                    '}' -> braceCount++
                    '{' -> braceCount--
                }
                if (braceCount == 0) {
                    start = i
                    break
                }
            }

            if (braceCount == 0) {
                val jsonStr = thinking.substring(start, lastBrace + 1)
                if (jsonStr.contains("\"tool\"") || jsonStr.contains("\"pipeline\"") ||
                    jsonStr.contains("\"action\"") || jsonStr.contains("\"goal\"")) {
                    logger.info("[AnimaFabric] 从思考内容提取的 JSON: {}", jsonStr.take(200))
                    return jsonStr
                }
            }
        }

        // 方式3：用正则找 JSON 对象
        val jsonPattern = Regex("""\{[\s\S]*"(tool|action|pipeline|goal)"[\s\S]*\}""")
        val match = jsonPattern.find(thinking)
        if (match != null) {
            logger.info("[AnimaFabric] 用正则提取的 JSON: {}", match.value.take(200))
            return match.value
        }

        logger.warn("[AnimaFabric] 无法从思考内容中提取有效内容")
        return ""
    }
}
