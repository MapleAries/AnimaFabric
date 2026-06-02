package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner
import kotlinx.serialization.json.*
import com.maple.agent.ActionPlanNode

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
        var executionHistory = ""
        val maxRetries = getMaxRetries()

        for (attempt in 0..maxRetries) {
            // 1. 世界感知（每次重试都刷新）
            val bot = server.playerList.getPlayerByName(botName)
            val worldState = if (bot != null) {
                WorldPerception.scan(bot)
            } else {
                "Bot not online"
            }

            // 2. 构建 LLM 请求（带执行历史和错误反馈）
            val systemPrompt = if (attempt == 0) {
                LLMPlanner.buildSystemPrompt(worldState, executionHistory)
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

            // 5. 检查输出（content 和 thinking 都为空才算失败）
            if (llmResponse.content.isBlank() && llmResponse.thinking.isBlank()) {
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
                is ParsedResponse.ActionPlan -> {
                    // 结构化行动方案（带循环和条件）
                    val planExecutor = PlanExecutor(botName, server, actionExecutor)
                    val result = planExecutor.execute(parsed.node)
                    val hasFailure = result.any { it.failed }

                    if (!hasFailure) {
                        val resultText = result.joinToString("\n") { it.message }
                        sendChatMessage(parsed.goal)
                        return resultText
                    }

                    lastError = buildStepErrorFeedback(result)
                    println("[AnimaFabric] Retry ${attempt + 1}/$maxRetries: action plan failed")
                }
                is ParsedResponse.Success -> {
                    if (parsed.commands.isEmpty()) {
                        if (parsed.message.isNotBlank()) {
                            sendChatMessage(parsed.message)
                        }
                        return parsed.message
                    }

                    val result = executeCommands(parsed.commands)
                    val hasFailure = result.any { it.failed }

                    // 构建执行历史（用于后续 LLM 调用）
                    executionHistory = result.mapIndexed { i, r ->
                        val status = if (r.failed) "失败" else "成功"
                        "${i+1}. [${r.toolName}] $status: ${r.message}"
                    }.joinToString("\n")

                    if (!hasFailure) {
                        val resultText = result.joinToString("\n") { it.message }
                        if (parsed.message.isNotBlank()) {
                            sendChatMessage(parsed.message)
                        }
                        return resultText
                    }

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
     * 构建 PlanExecutor 的错误反馈。
     */
    private fun buildStepErrorFeedback(results: List<PlanExecutor.StepResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Your previous action plan had failures:")
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
                // 结构化行动方案（带循环和条件）
                "goal" in obj && "steps" in obj -> {
                    val goal = obj["goal"]!!.jsonPrimitive.content
                    val actionPlan = parseActionPlan(obj)
                    if (actionPlan != null) {
                        return ParsedResponse.ActionPlan(goal, actionPlan)
                    }
                    return ParsedResponse.Success(goal, emptyList())
                }
                // 传统格式
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
     * 解析结构化行动方案（ActionPlan）。
     */
    private fun parseActionPlan(obj: JsonObject): ActionPlanNode? {
        val steps = obj["steps"]?.jsonArray ?: return null
        val nodes = steps.mapNotNull { parseNode(it.jsonObject) }
        return if (nodes.size == 1) nodes[0] else ActionPlanNode.Sequential(nodes)
    }

    private fun parseNode(obj: JsonObject): ActionPlanNode? {
        return when {
            "action" in obj -> {
                val tool = obj["action"]!!.jsonPrimitive.content
                val params = parseJsonParams(obj["parameters"]?.jsonObject)
                ActionPlanNode.Action(tool, params)
            }
            "loop" in obj -> {
                val loopObj = obj["loop"]!!.jsonObject
                val steps = loopObj["steps"]?.jsonArray?.mapNotNull { parseNode(it.jsonObject) } ?: emptyList()
                val until = parseCondition(loopObj["until"]?.jsonObject)
                val whileCond = parseCondition(loopObj["while"]?.jsonObject)
                val maxIter = loopObj["max_iterations"]?.jsonPrimitive?.intOrNull ?: 20
                ActionPlanNode.Loop(steps, until, whileCond, maxIter)
            }
            "conditional" in obj -> {
                val condObj = obj["conditional"]!!.jsonObject
                val condition = parseCheckCondition(condObj["check"]?.jsonObject) ?: return null
                val thenSteps = condObj["then"]?.jsonArray?.mapNotNull { parseNode(it.jsonObject) } ?: emptyList()
                val elseSteps = condObj["else"]?.jsonArray?.mapNotNull { parseNode(it.jsonObject) } ?: emptyList()
                ActionPlanNode.Conditional(condition, thenSteps, elseSteps)
            }
            "delay" in obj -> {
                val ms = obj["delay"]!!.jsonPrimitive.longOrNull ?: 1000L
                ActionPlanNode.Delay(ms)
            }
            else -> null
        }
    }

    private fun parseCondition(obj: JsonObject?): LoopCondition? {
        if (obj == null) return null
        val type = parseConditionType(obj["type"]?.jsonPrimitive?.content) ?: return null
        return LoopCondition(
            type = type,
            item = obj["item"]?.jsonPrimitive?.content,
            count = obj["count"]?.jsonPrimitive?.intOrNull,
            pos = parseBlockPos(obj["pos"]?.jsonObject),
            blockState = obj["block_state"]?.jsonPrimitive?.content,
            health = obj["health"]?.jsonPrimitive?.floatOrNull
        )
    }

    private fun parseCheckCondition(obj: JsonObject?): CheckCondition? {
        if (obj == null) return null
        val type = parseConditionType(obj["type"]?.jsonPrimitive?.content) ?: return null
        return CheckCondition(
            type = type,
            item = obj["item"]?.jsonPrimitive?.content,
            count = obj["count"]?.jsonPrimitive?.intOrNull,
            pos = parseBlockPos(obj["pos"]?.jsonObject),
            blockState = obj["block_state"]?.jsonPrimitive?.content,
            health = obj["health"]?.jsonPrimitive?.floatOrNull
        )
    }

    private fun parseConditionType(type: String?): ConditionType? {
        return when (type) {
            "inventory_contains" -> ConditionType.INVENTORY_CONTAINS
            "inventory_full" -> ConditionType.INVENTORY_FULL
            "block_at" -> ConditionType.BLOCK_AT
            "health_below" -> ConditionType.HEALTH_BELOW
            "health_above" -> ConditionType.HEALTH_ABOVE
            "always" -> ConditionType.ALWAYS
            "never" -> ConditionType.NEVER
            else -> null
        }
    }

    private fun parseBlockPos(obj: JsonObject?): BlockPos3? {
        if (obj == null) return null
        val x = obj["x"]?.jsonPrimitive?.intOrNull ?: return null
        val y = obj["y"]?.jsonPrimitive?.intOrNull ?: return null
        val z = obj["z"]?.jsonPrimitive?.intOrNull ?: return null
        return BlockPos3(x, y, z)
    }

    private fun parseJsonParams(obj: JsonObject?): Map<String, Any> {
        if (obj == null) return emptyMap()
        val params = mutableMapOf<String, Any>()
        obj.forEach { (key, value) ->
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
        return params
    }

    /**
     * 执行命令列表，返回每个步骤的结果。
     */
    private suspend fun executeCommands(commands: List<ParsedCommand>): List<StepResult> {
        // 去重：移除连续重复的命令（如多个 !sneak()）
        val deduplicated = mutableListOf<ParsedCommand>()
        for (cmd in commands) {
            val last = deduplicated.lastOrNull()
            if (last == null || last.tool != cmd.tool || last.params != cmd.params) {
                deduplicated.add(cmd)
            }
        }

        // 限制最多 6 个命令
        val limited = deduplicated.take(6)
        if (limited.size < commands.size) {
            println("[AnimaFabric] 命令去重/限制: ${commands.size} → ${limited.size}")
        }

        val results = mutableListOf<StepResult>()

        for ((index, command) in limited.withIndex()) {
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
        data class ActionPlan(val goal: String, val node: ActionPlanNode) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }
}
