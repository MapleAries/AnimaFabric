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
            is ParsedResponse.ToolCall -> {
                executeStep(parsed.step)
            }
            is ParsedResponse.Pipeline -> {
                executePipeline(parsed.steps)
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
        val maxLength = 100 // Minecraft 聊天框每行约 100 个字符

        if (message.length <= maxLength) {
            server.playerList.broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal("[$botName] $message"),
                false
            )
        } else {
            // 分割长消息
            val lines = message.chunked(maxLength)
            for (line in lines) {
                server.playerList.broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("[$botName] $line"),
                    false
                )
            }
        }
    }

    private fun parseResponse(response: String): ParsedResponse {
        return try {
            val jsonStr = extractJson(response)
            val element = Json.parseToJsonElement(jsonStr)
            val obj = element.jsonObject

            when {
                "clarification" in obj -> {
                    ParsedResponse.Clarification(obj["clarification"]!!.jsonPrimitive.content)
                }
                "pipeline" in obj -> {
                    val steps = obj["pipeline"]!!.jsonArray.map { parseStep(it.jsonObject) }
                    ParsedResponse.Pipeline(steps)
                }
                "tool" in obj -> {
                    ParsedResponse.ToolCall(parseStep(obj))
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

    private fun parseStep(obj: JsonObject): PipelineStep {
        val tool = obj["tool"]!!.jsonPrimitive.content
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
        println("[MC-Mind] 解析步骤: tool=$tool, params=$params")
        return PipelineStep(tool, params)
    }

    private suspend fun executePipeline(steps: List<PipelineStep>): String {
        val results = mutableListOf<String>()

        for ((index, step) in steps.withIndex()) {
            val resolvedParams = sharedState.resolveAll(
                step.params.mapValues { it.value.toString() }
            )

            val result = executeStep(PipelineStep(step.tool, resolvedParams))
            results.add("步骤 ${index + 1} [${step.tool}]: $result")

            if (step.tool == "moveTo") {
                sharedState.set("moveTo_result", result)
            } else if (step.tool == "mineBlock") {
                sharedState.set("mineBlock_result", result)
            }

            if (result.startsWith("失败") || result.startsWith("错误")) {
                break
            }
        }

        return results.joinToString("\n")
    }

    private suspend fun executeStep(step: PipelineStep): String {
        return actionExecutor.execute(step.tool, step.params)
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
        data class ToolCall(val step: PipelineStep) : ParsedResponse()
        data class Pipeline(val steps: List<PipelineStep>) : ParsedResponse()
        data class Clarification(val message: String) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }
}
