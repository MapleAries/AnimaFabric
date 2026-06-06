package com.maple.llm

import com.maple.config.AnimaFabricConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLHandshakeException

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
        onContent: (String) -> Unit = {},
        extractActions: Boolean = true
    ): LLMResponse = withContext(Dispatchers.IO) {
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            try {
                val request = ChatRequest(
                    model = config.model,
                    messages = messages,
                    maxTokens = config.maxTokens,
                    stream = true
                )

                val requestBody = json.encodeToString(request)
                logger.info("[AnimaFabric] 发送 LLM 请求: URL={}, Model={} (attempt {}/{})", config.apiUrl, config.model, attempt, maxAttempts)

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

                val rawThinking = thinkingBuilder.toString()
                val rawContent = contentBuilder.toString()
                val finalContent = if (extractActions) {
                    processResponse(rawThinking, rawContent)
                } else {
                    rawContent.ifBlank { rawThinking }
                }

                return@withContext LLMResponse(rawThinking, finalContent)
            } catch (e: java.net.SocketTimeoutException) {
                logger.error("[AnimaFabric] LLM 请求超时（{}秒），请检查网络或增大 timeout 配置", config.timeout)
                return@withContext LLMResponse("", "")
            } catch (e: SSLHandshakeException) {
                if (attempt < maxAttempts) {
                    logger.warn("[AnimaFabric] LLM TLS 握手中断，将重试: {}", e.message)
                    delay(500L * attempt)
                } else {
                    logger.warn("[AnimaFabric] LLM TLS 握手连续失败: {}", e.message)
                    return@withContext LLMResponse("", "")
                }
            } catch (e: Exception) {
                logger.error("[AnimaFabric] LLM 请求异常: {}", e.message, e)
                return@withContext LLMResponse("", "")
            }
        }
        LLMResponse("", "")
    }

    /**
     * 处理响应内容，提取有效的 JSON。
     * 参考 AI-Player 的处理方式：
     * 1. 剥离思考内容（<think>...</think> 块）
     * 2. 从 content 中提取 JSON
     * 3. 如果 content 为空，从 reasoning_content 中提取
     */
    private fun processResponse(thinking: String, content: String): String {
        // 如果有实际内容，先尝试从中提取
        if (content.isNotBlank()) {
            // 尝试提取命令
            val cmdResult = extractCommands(content)
            if (cmdResult.isNotBlank()) return cmdResult
            // 尝试提取 JSON
            val jsonResult = extractJson(content)
            if (jsonResult.isNotBlank()) return jsonResult
            // 直接返回原始内容
            return content
        }

        // content 为空，从思考内容中提取
        if (thinking.isNotBlank()) {
            logger.info("[AnimaFabric] 从思考内容中提取（content 为空）")
            // 先尝试提取命令
            val cmdResult = extractCommands(thinking)
            if (cmdResult.isNotBlank()) {
                logger.info("[AnimaFabric] 从思考内容提取到命令")
                return cmdResult
            }
            // 再尝试提取 JSON
            val jsonResult = extractJsonFromThinking(thinking)
            if (jsonResult.isNotBlank()) return jsonResult
        }

        return ""
    }

    /**
     * 从文本中提取所有 !tool(args) 命令。
     * 过滤掉工具描述（包含类型注解的）。
     */
    private fun extractCommands(text: String): String {
        val commandPattern = Regex("""!(\w+)\(([^)]*)\)""")
        val typePattern = Regex("""\w+:\s*(string|number|boolean|int|float|double)""")
        val nameOnlyPattern = Regex("""^[a-zA-Z_]+$""")

        val commands = commandPattern.findAll(text)
            .filter { match ->
                val args = match.groupValues[2].trim()
                if (args.isEmpty()) return@filter true // 无参数命令如 !jump()
                // 过滤类型注解
                if (typePattern.containsMatchIn(args)) return@filter false
                // 过滤纯参数名（如 direction, ticks）
                val parts = args.split(",").map { it.trim() }
                val allNames = parts.all { it.isNotEmpty() && nameOnlyPattern.matches(it) }
                !allNames
            }
            .map { it.value }
            .toList()

        return if (commands.isNotEmpty()) {
            commands.joinToString(" ")
        } else {
            ""
        }
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
        // 过滤掉工具描述，只保留实际调用
        val commandPattern = Regex("""!(\w+)\(([^)]*)\)""")
        val typePattern = Regex("""\w+:\s*(string|number|boolean|int|float|double)""")
        val commandMatches = commandPattern.findAll(thinking)
            .filter { match ->
                val args = match.groupValues[2].trim()
                // 过滤条件1：包含类型注解 (direction: string)
                val hasTypeAnnotation = typePattern.containsMatchIn(args)
                // 过滤条件2：所有参数都是纯字母单词（参数名，不是值）
                val allParamNames = args.split(",").all { part ->
                    val trimmed = part.trim()
                    trimmed.isNotEmpty() && trimmed.matches(Regex("[a-zA-Z_]+"))
                }
                // 保留：既没有类型注解，也不是纯参数名
                !hasTypeAnnotation && !allParamNames
            }
            .map { it.value }
            .toList()
        if (commandMatches.isNotEmpty()) {
            val result = commandMatches.joinToString(" ")
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
