package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner
import kotlinx.serialization.json.*

/**
 * 管线解析 + 执行。
 * 解析 LLM JSON 响应并执行工具调用管线。
 */
class PipelineExecutor(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val llmClient: LLMClient,
    private val memory: ConversationMemory,
    private val actionExecutor: ActionExecutor
) {
    private val sharedState = SharedState()

    /**
     * 处理用户指令：构建提示词、调用 LLM、解析响应、执行管线。
     */
    suspend fun processCommand(command: String): String {
        // 1. 世界感知
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) {
            WorldPerception.scan(bot)
        } else {
            "Bot 不在线"
        }

        // 2. 构建 LLM 请求
        val systemPrompt = LLMPlanner.buildSystemPrompt(worldState)
        memory.addUserMessage(command)

        val messages = mutableListOf(ChatMessage("system", systemPrompt))
        messages.addAll(memory.getMessages())

        // 3. 调用 LLM（流式）
        val llmResponse = llmClient.chatStream(messages)

        // 4. 记录思考内容到控制台（不显示在游戏内）
        if (llmResponse.thinking.isNotBlank()) {
            println("[MC-Mind] LLM 思考过程: ${llmResponse.thinking.take(200)}...")
        }

        // 5. 检查实际输出
        if (llmResponse.content.isBlank()) {
            return "LLM 未返回有效内容，请重试。"
        }

        println("[MC-Mind] LLM 实际输出: ${llmResponse.content}")

        // 6. 解析响应
        val parsed = parseResponse(llmResponse.content)
        memory.addAssistantMessage(llmResponse.content)

        // 7. 执行
        return when (parsed) {
            is ParsedResponse.Success -> {
                // 显示计划
                sendChatMessage("${parsed.plan}")
                // 执行任务
                executeTasks(parsed.tasks)
            }
            is ParsedResponse.Clarification -> {
                parsed.message
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
     * 1. 新格式：{"reasoning": "...", "plan": "...", "tasks": [...]}
     * 2. 旧格式：{"tool": "...", "parameters": {...}} 或 {"pipeline": [...]}
     */
    private fun parseResponse(response: String): ParsedResponse {
        return try {
            val jsonStr = extractJson(response)
            val element = Json.parseToJsonElement(jsonStr)
            val obj = element.jsonObject

            when {
                // 新格式：reasoning + plan + tasks
                "tasks" in obj -> {
                    val plan = obj["plan"]?.jsonPrimitive?.content ?: "执行任务"
                    val tasks = obj["tasks"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    ParsedResponse.Success(plan, tasks)
                }
                // 旧格式：单个工具调用
                "tool" in obj -> {
                    val task = parseTask(obj)
                    ParsedResponse.Success("执行 ${task.tool}", listOf(task))
                }
                // 旧格式：管线
                "pipeline" in obj -> {
                    val tasks = obj["pipeline"]!!.jsonArray.map { parseTask(it.jsonObject) }
                    ParsedResponse.Success("执行管线", tasks)
                }
                // 澄清请求
                "clarification" in obj -> {
                    ParsedResponse.Clarification(obj["clarification"]!!.jsonPrimitive.content)
                }
                else -> ParsedResponse.Error("无法解析 LLM 响应：$response")
            }
        } catch (e: Exception) {
            // 如果 JSON 解析失败，检查是否是自然语言回复
            if (response.isNotBlank() && !response.trimStart().startsWith("{")) {
                println("[MC-Mind] LLM 返回自然语言，作为澄清请求处理")
                ParsedResponse.Clarification(response)
            } else {
                ParsedResponse.Error("解析 LLM 响应失败：${e.message}")
            }
        }
    }

    /**
     * 解析单个任务。
     * 支持两种格式：
     * 1. 新格式：{"action": "...", "parameters": {...}}
     * 2. 旧格式：{"tool": "...", "parameters": {...}}
     */
    private fun parseTask(obj: JsonObject): PipelineStep {
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
        println("[MC-Mind] 解析任务: action=$tool, params=$params")
        return PipelineStep(tool, params)
    }

    /**
     * 执行任务列表。
     */
    private suspend fun executeTasks(tasks: List<PipelineStep>): String {
        val results = mutableListOf<String>()

        for ((index, task) in tasks.withIndex()) {
            val resolvedParams = sharedState.resolveAll(
                task.params.mapValues { it.value.toString() }
            )

            val result = actionExecutor.execute(task.tool, resolvedParams)
            results.add("步骤 ${index + 1} [${task.tool}]: $result")

            // 更新共享状态
            if (task.tool == "moveTo") {
                sharedState.set("moveTo_result", result)
            } else if (task.tool == "mineBlock") {
                sharedState.set("mineBlock_result", result)
            }

            // 如果步骤失败，停止执行
            if (result.startsWith("失败") || result.startsWith("错误") || result.startsWith("挖掘失败")) {
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

    data class PipelineStep(
        val tool: String,
        val params: Map<String, Any>
    )

    sealed class ParsedResponse {
        data class Success(val plan: String, val tasks: List<PipelineStep>) : ParsedResponse()
        data class Clarification(val message: String) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }
}
