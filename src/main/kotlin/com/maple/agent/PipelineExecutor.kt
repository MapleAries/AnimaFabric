package com.maple.agent

import com.maple.entity.FakePlayer
import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner
import kotlinx.serialization.json.*

/**
 * 管线解析 + 执行。
 * 解析 LLM JSON 响应并执行工具调用管线。
 */
class PipelineExecutor(
    private val bot: FakePlayer,
    private val llmClient: LLMClient,
    private val memory: ConversationMemory
) {
    private val actionExecutor = ActionExecutor(bot)
    private val sharedState = SharedState()

    /**
     * 处理用户指令：构建提示词、调用 LLM、解析响应、执行管线。
     */
    suspend fun processCommand(command: String): String {
        // 1. 世界感知
        val worldState = com.maple.agent.WorldPerception.scan(bot)

        // 2. 构建 LLM 请求
        val systemPrompt = LLMPlanner.buildSystemPrompt(worldState)
        memory.addUserMessage(command)

        val messages = mutableListOf(ChatMessage("system", systemPrompt))
        messages.addAll(memory.getMessages())

        // 3. 调用 LLM（流式）
        val responseBuilder = StringBuilder()
        val response = llmClient.chatStream(messages) { chunk ->
            responseBuilder.append(chunk)
        }

        if (response.isEmpty()) {
            return "LLM 请求失败，请检查配置。"
        }

        // 4. 解析响应
        val parsed = parseResponse(response)
        memory.addAssistantMessage(response)

        // 5. 执行
        return when (parsed) {
            is ParsedResponse.ToolCall -> {
                val result = executeStep(parsed.step)
                result
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
            ParsedResponse.Error("解析 LLM 响应失败：${e.message}")
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
        return PipelineStep(tool, params)
    }

    private suspend fun executePipeline(steps: List<PipelineStep>): String {
        val results = mutableListOf<String>()

        for ((index, step) in steps.withIndex()) {
            // 解析占位符
            val resolvedParams = sharedState.resolveAll(
                step.params.mapValues { it.value.toString() }
            )

            val result = executeStep(PipelineStep(step.tool, resolvedParams))
            results.add("步骤 ${index + 1} [${step.tool}]: $result")

            // 更新共享状态
            if (step.tool == "moveTo") {
                sharedState.set("moveTo_result", result)
            } else if (step.tool == "mineBlock") {
                sharedState.set("mineBlock_result", result)
            }

            // 如果步骤失败，停止执行
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
        // 尝试从文本中提取 JSON
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
