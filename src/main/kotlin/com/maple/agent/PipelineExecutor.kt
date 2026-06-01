package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner
import kotlinx.serialization.json.*

/**
 * 管线解析 + 执行。
 * 解析 LLM 响应并执行命令。
 * 支持两种格式：
 * 1. 命令式格式：!toolName(param1, param2)
 * 2. JSON 格式：{"action": "toolName", "parameters": {...}}
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
     */
    suspend fun processCommand(command: String): String {
        // 1. 世界感知
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) {
            WorldPerception.scan(bot)
        } else {
            "Bot not online"
        }

        // 2. 构建 LLM 请求
        val systemPrompt = LLMPlanner.buildSystemPrompt(worldState)
        memory.addUserMessage(command)

        val messages = mutableListOf(ChatMessage("system", systemPrompt))
        messages.addAll(memory.getMessages())

        // 3. 调用 LLM（流式）
        val llmResponse = llmClient.chatStream(messages)

        // 4. 记录思考内容到控制台
        if (llmResponse.thinking.isNotBlank()) {
            println("[MC-Mind] LLM thinking: ${llmResponse.thinking.take(200)}...")
        }

        // 5. 检查实际输出
        if (llmResponse.content.isBlank()) {
            return "LLM returned no content, please try again."
        }

        println("[MC-Mind] LLM output: ${llmResponse.content}")

        // 6. 解析响应
        val parsed = parseResponse(llmResponse.content)
        memory.addAssistantMessage(llmResponse.content)

        // 7. 执行
        return when (parsed) {
            is ParsedResponse.Success -> {
                // 显示自然语言部分
                if (parsed.message.isNotBlank()) {
                    sendChatMessage(parsed.message)
                }
                // 执行命令
                executeCommands(parsed.commands)
            }
            is ParsedResponse.Error -> {
                parsed.message
            }
        }
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
                // 新格式：reasoning + plan + tasks
                "tasks" in obj -> {
                    val plan = obj["plan"]?.jsonPrimitive?.content ?: ""
                    val tasks = obj["tasks"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    return ParsedResponse.Success(plan, tasks)
                }
                // 旧格式：单个工具调用
                "tool" in obj || "action" in obj -> {
                    val task = parseTask(obj)
                    return ParsedResponse.Success("", listOf(task))
                }
                // 旧格式：管线
                "pipeline" in obj -> {
                    val tasks = obj["pipeline"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    return ParsedResponse.Success("", tasks)
                }
                // 澄清请求
                "clarification" in obj -> {
                    return ParsedResponse.Success(obj["clarification"]!!.jsonPrimitive.content, emptyList())
                }
            }
        } catch (e: Exception) {
            // JSON 解析失败，尝试作为自然语言回复
        }

        // 如果没有找到命令，返回自然语言消息
        return ParsedResponse.Success(response, emptyList())
    }

    /**
     * 解析命令参数。
     * 支持：数字、字符串、布尔值
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
     * 执行命令列表。
     */
    private suspend fun executeCommands(commands: List<ParsedCommand>): String {
        val results = mutableListOf<String>()

        for ((index, command) in commands.withIndex()) {
            val resolvedParams = sharedState.resolveAll(
                command.params.mapValues { it.value.toString() }
            )

            val result = actionExecutor.execute(command.tool, resolvedParams)
            results.add("Step ${index + 1} [${command.tool}]: $result")

            // 更新共享状态
            if (command.tool == "moveTo") {
                sharedState.set("moveTo_result", result)
            } else if (command.tool == "mineBlock") {
                sharedState.set("mineBlock_result", result)
            }

            // 如果步骤失败，停止执行
            if (result.startsWith("Failed") || result.startsWith("Error") || result.startsWith("Mining failed")) {
                break
            }
        }

        return results.joinToString("\n")
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || start >= end) {
            return text
        }
        return text.substring(start, end + 1)
    }

    data class ParsedCommand(
        val tool: String,
        val params: Map<String, Any>
    )

    sealed class ParsedResponse {
        data class Success(val message: String, val commands: List<ParsedCommand>) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }
}
