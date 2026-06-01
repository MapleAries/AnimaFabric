package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner
import kotlinx.serialization.json.*

/**
 * 管线解析 + 执行，支持错误反馈重试。
 *
 * 核心流程（Voyager 风格）：
 * 1. 获取世界状态 → 构建 prompt → 调用 LLM → 解析 → 执行
 * 2. 如果执行失败，将错误信息回传 LLM，让它修正方案后重试
 * 3. 最多重试 maxRetries 次
 *
 * 支持两种 LLM 响应格式：
 * 1. 命令式：!toolName(param1, param2)
 * 2. JSON：{"action": "toolName", "parameters": {...}}
 */
class PipelineExecutor(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val llmClient: LLMClient,
    private val memory: ConversationMemory,
    private val actionExecutor: ActionExecutor
) {
    private val sharedState = SharedState()

    // 命令正则表达式：!commandName(param1, param2, ...)
    private val commandRegex = Regex("""!(\w+)\(([^)]*)\)""")

    /**
     * 处理用户指令：构建提示词、调用 LLM、解析响应、执行命令。
     * 失败时自动将错误反馈给 LLM 重试。
     */
    suspend fun processCommand(command: String): String {
        var lastError: String? = null
        val maxRetries = getMaxRetries()

        for (attempt in 0..maxRetries) {
            // 1. 世界感知（每次重试都刷新）
            val bot = server.playerList.getPlayerByName(botName)
            val worldState = if (bot != null) {
                WorldPerception.scan(bot)
            } else {
                "Bot not online"
            }

            // 2. 构建 LLM 请求（如果是重试，附加错误反馈）
            val systemPrompt = if (attempt == 0) {
                LLMPlanner.buildSystemPrompt(worldState)
            } else {
                LLMPlanner.buildRetryPrompt(worldState, lastError!!, attempt)
            }

            // 只在第一次尝试时添加用户消息到记忆
            if (attempt == 0) {
                memory.addUserMessage(command)
            }

            val messages = mutableListOf(ChatMessage("system", systemPrompt))
            messages.addAll(memory.getMessages())

            // 3. 调用 LLM（流式）
            val llmResponse = llmClient.chatStream(messages)

            // 4. 记录思考内容
            if (llmResponse.thinking.isNotBlank()) {
                println("[AnimaFabric] LLM thinking (attempt ${attempt + 1}): ${llmResponse.thinking.take(200)}...")
            }

            // 5. 检查输出
            if (llmResponse.content.isBlank()) {
                lastError = "LLM returned no content"
                println("[AnimaFabric] Retry ${attempt + 1}/$maxRetries: $lastError")
                continue
            }

            println("[AnimaFabric] LLM output (attempt ${attempt + 1}): ${llmResponse.content}")

            // 6. 解析响应
            val parsed = parseResponse(llmResponse.content)
            if (attempt == 0) {
                memory.addAssistantMessage(llmResponse.content)
            }

            // 7. 执行
            when (parsed) {
                is ParsedResponse.Success -> {
                    if (parsed.commands.isEmpty()) {
                        // 纯自然语言回复，视为成功
                        if (parsed.message.isNotBlank()) {
                            sendChatMessage(parsed.message)
                        }
                        return parsed.message
                    }

                    // 执行命令
                    val result = executeCommands(parsed.commands)

                    // 检查是否有失败的步骤
                    val hasFailure = result.any { it.failed }

                    if (!hasFailure) {
                        // 全部成功
                        val resultText = result.joinToString("\n") { it.message }
                        if (parsed.message.isNotBlank()) {
                            sendChatMessage(parsed.message)
                        }
                        return resultText
                    }

                    // 有失败，准备重试
                    lastError = buildErrorFeedback(result)
                    println("[AnimaFabric] Retry ${attempt + 1}/$maxRetries: execution failed, feeding error back to LLM")
                    println("[AnimaFabric] Error feedback: $lastError")
                }
                is ParsedResponse.Error -> {
                    lastError = parsed.message
                    println("[AnimaFabric] Retry ${attempt + 1}/$maxRetries: parse error: $lastError")
                }
            }
        }

        // 所有重试都失败
        val finalMessage = "指令执行失败（已重试 $maxRetries 次）：$lastError"
        sendChatMessage("抱歉，我没能完成这个任务。$lastError")
        return finalMessage
    }

    /**
     * 从配置获取最大重试次数。
     */
    private fun getMaxRetries(): Int {
        return try {
            com.maple.config.AnimaFabricConfig.load().maxRetries
        } catch (e: Exception) {
            3
        }
    }

    /**
     * 构建错误反馈信息，用于发送给 LLM。
     */
    private fun buildErrorFeedback(results: List<StepResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Your previous plan had failures:")
        for ((index, result) in results.withIndex()) {
            val status = if (result.failed) "FAILED" else "OK"
            sb.appendLine("- Step ${index + 1} [${result.toolName}]: $status - ${result.message}")
        }
        return sb.toString().trim()
    }

    /**
     * 发送聊天消息，自动分割长消息。
     */
    private fun sendChatMessage(message: String) {
        val maxLength = 100

        if (message.length <= maxLength) {
            server.playerList.broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal("[$botName] $message"),
                false
            )
        } else {
            val lines = message.chunked(maxLength)
            for (line in lines) {
                server.playerList.broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("[$botName] $line"),
                    false
                )
            }
        }
    }

    /**
     * 解析 LLM 响应。
     * 支持两种格式：
     * 1. 命令式格式：!toolName(param1, param2)
     * 2. JSON 格式：{"action": "toolName", "parameters": {...}}
     */
    private fun parseResponse(response: String): ParsedResponse {
        val commands = mutableListOf<ParsedCommand>()
        var message = response

        // 尝试解析命令式格式
        val matches = commandRegex.findAll(response)
        for (match in matches) {
            val toolName = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val params = parseParams(paramsStr)
            commands.add(ParsedCommand(toolName, params))

            // 从消息中移除命令部分
            message = message.replace(match.value, "").trim()
        }

        // 如果找到命令，返回成功
        if (commands.isNotEmpty()) {
            return ParsedResponse.Success(message, commands)
        }

        // 尝试解析 JSON 格式
        try {
            val jsonStr = extractJson(response)
            val element = Json.parseToJsonElement(jsonStr)
            val obj = element.jsonObject

            when {
                "tasks" in obj -> {
                    val plan = obj["plan"]?.jsonPrimitive?.content ?: ""
                    val tasks = obj["tasks"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    return ParsedResponse.Success(plan, tasks)
                }
                "tool" in obj || "action" in obj -> {
                    val task = parseTask(obj)
                    return ParsedResponse.Success("", listOf(task))
                }
                "pipeline" in obj -> {
                    val tasks = obj["pipeline"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    return ParsedResponse.Success("", tasks)
                }
                "clarification" in obj -> {
                    return ParsedResponse.Success(obj["clarification"]!!.jsonPrimitive.content, emptyList())
                }
            }
        } catch (e: Exception) {
            // JSON 解析失败，尝试作为自然语言回复
        }

        return ParsedResponse.Success(response, emptyList())
    }

    /**
     * 解析命令参数。
     */
    private fun parseParams(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        if (paramsStr.isBlank()) return params

        val parts = paramsStr.split(",").map { it.trim() }
        for ((index, part) in parts.withIndex()) {
            val value = when {
                part.equals("true", ignoreCase = true) -> true
                part.equals("false", ignoreCase = true) -> false
                part.toDoubleOrNull() != null -> part.toDouble()
                part.startsWith("\"") && part.endsWith("\"") -> part.substring(1, part.length - 1)
                else -> part
            }
            params["param$index"] = value
        }

        return params
    }

    /**
     * 解析 JSON 任务。
     */
    private fun parseTask(obj: JsonObject): ParsedCommand {
        val tool = (obj["action"] ?: obj["tool"])!!.jsonPrimitive.content
        val params = mutableMapOf<String, Any>()
        obj["parameters"]?.jsonObject?.forEach { (key, value) ->
            params[key] = when (value) {
                is JsonPrimitive -> {
                    when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        else -> value.doubleOrNull ?: value.intOrNull ?: value.content
                    }
                }
                else -> value.toString()
            }
        }
        return ParsedCommand(tool, params)
    }

    /**
     * 执行命令列表，返回每个步骤的结果。
     */
    private suspend fun executeCommands(commands: List<ParsedCommand>): List<StepResult> {
        val results = mutableListOf<StepResult>()

        for ((index, command) in commands.withIndex()) {
            val resolvedParams = sharedState.resolveAll(
                command.params.mapValues { it.value.toString() }
            )

            val result = actionExecutor.execute(command.tool, resolvedParams)

            val failed = result.startsWith("Failed") ||
                         result.startsWith("Error") ||
                         result.startsWith("挖掘失败") ||
                         result.startsWith("移动未完成") ||
                         result.startsWith("Bot 不存在") ||
                         result.startsWith("未知工具")

            results.add(StepResult(command.tool, result, failed))

            // 更新共享状态
            if (command.tool == "moveTo") {
                sharedState.set("moveTo_result", result)
            } else if (command.tool == "mineBlock") {
                sharedState.set("mineBlock_result", result)
            }

            // 如果步骤失败，停止执行后续步骤
            if (failed) {
                break
            }
        }

        return results
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || start >= end) {
            return text
        }
        return text.substring(start, end + 1)
    }

    data class StepResult(
        val toolName: String,
        val message: String,
        val failed: Boolean
    )

    data class ParsedCommand(
        val tool: String,
        val params: Map<String, Any>
    )

    sealed class ParsedResponse {
        data class Success(val message: String, val commands: List<ParsedCommand>) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }
}
