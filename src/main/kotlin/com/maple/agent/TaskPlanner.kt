package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import com.maple.llm.LLMPlanner

/**
 * 通用复杂任务处理器。
 *
 * 核心流程：
 * 1. 接收自然语言指令
 * 2. LLM 分解为步骤序列
 * 3. 逐步执行，每步反馈结果
 * 4. 失败时自适应重新规划
 * 5. 成功后记录技能供复用
 *
 * 与 PipelineExecutor 的区别：
 * - PipelineExecutor: 单次 LLM 调用 → 执行命令序列
 * - TaskPlanner: 多轮 LLM 交互 → 分步执行 → 自适应调整
 */
class TaskPlanner(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val llmClient: LLMClient,
    private val actionExecutor: ActionExecutor
) {
    /** 当前任务的步骤列表 */
    private val steps = mutableListOf<TaskStep>()

    /** 当前执行到的步骤索引 */
    private var currentStepIndex = 0

    /** 任务是否完成 */
    var isComplete = false
        private set

    /** 任务是否失败 */
    var isFailed = false
        private set

    /** 失败原因 */
    var failReason = ""
        private set

    /**
     * 处理一个复杂任务。
     * 返回执行结果摘要。
     */
    suspend fun processTask(command: String): String {
        // 1. 分解任务
        val decomposed = decomposeTask(command)
        if (decomposed.isEmpty()) {
            return "无法分解任务：$command"
        }

        steps.clear()
        steps.addAll(decomposed)
        currentStepIndex = 0
        isComplete = false
        isFailed = false

        val results = mutableListOf<String>()
        var consecutiveFailures = 0

        // 2. 逐步执行
        while (currentStepIndex < steps.size && !isFailed) {
            val step = steps[currentStepIndex]
            println("[AnimaFabric] 执行步骤 ${currentStepIndex + 1}/${steps.size}: ${step.description}")

            val result = executeStep(step)
            step.result = result
            step.completed = !isFailed

            results.add("步骤 ${currentStepIndex + 1}: ${step.description} → $result")

            if (isFailed) {
                consecutiveFailures++
                if (consecutiveFailures >= 2) {
                    // 连续失败，尝试重新规划剩余步骤
                    println("[AnimaFabric] 连续失败，尝试重新规划...")
                    val replanned = replanRemaining(command, results)
                    if (replanned.isNotEmpty()) {
                        // 替换剩余步骤
                        val remaining = steps.subList(currentStepIndex + 1, steps.size)
                        remaining.clear()
                        remaining.addAll(replanned)
                        isFailed = false
                        consecutiveFailures = 0
                        continue
                    }
                    break
                }
                // 单次失败，重试当前步骤
                isFailed = false
                continue
            }

            consecutiveFailures = 0
            currentStepIndex++
        }

        if (!isFailed && currentStepIndex >= steps.size) {
            isComplete = true
        }

        return results.joinToString("\n")
    }

    /**
     * 分解任务为步骤序列。
     */
    private suspend fun decomposeTask(command: String): List<TaskStep> {
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) WorldPerception.scan(bot) else "Bot not online"

        val prompt = """你是一个 Minecraft AI 任务规划器。将任务分解为命令步骤。

## 当前世界状态
$worldState

## 可用命令
!moveTo(x, y, z) — 移动到坐标
!move(direction, 格数) — 短距离移动（forward/backward/left/right）
!turn(direction) — 转向（left/right/back）
!jump() — 跳跃
!sneak() — 切换潜行
!mineBlock(x, y, z) — 挖掘方块
!placeBlock(x, y, z, block) — 放置方块
!scanArea(radius) — 扫描周围
!getInventory() — 查看背包
!attack() — 攻击
!use() — 使用物品
!sendMessage(msg) — 发送消息

## 输出格式
每行一个命令，直接输出 !命令，不要输出其他内容。

## 示例
输入："走到树旁边挖木头"
!moveTo(10, 64, -5)
!mineBlock(10, 65, -5)
!mineBlock(10, 64, -5)

输入："蹲下然后站起来"
!sneak()
!sneak()

输入："往前走5步跳一下"
!move(forward, 5)
!jump()
"""

        val messages = listOf(
            ChatMessage("system", prompt),
            ChatMessage("user", command)
        )

        val response = llmClient.chatStream(messages)
        println("[AnimaFabric] 任务分解结果: ${response.content}")

        return parseSteps(response.content)
    }

    /**
     * 重新规划剩余步骤。
     */
    private suspend fun replanRemaining(originalTask: String, completedResults: List<String>): List<TaskStep> {
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) WorldPerception.scan(bot) else "Bot not online"

        val completed = completedResults.joinToString("\n")

        val prompt = """你是一个 Minecraft AI 任务规划器。之前的执行遇到了问题，请重新规划剩余步骤。

## 原始任务
$originalTask

## 当前世界状态
$worldState

## 已完成的步骤
$completed

## 要求
1. 根据已完成的步骤和当前状态，规划剩余步骤
2. 每行格式：步骤描述 | !命令
3. 最多 5 个步骤
4. 避免重复已失败的操作
"""

        val messages = listOf(
            ChatMessage("system", prompt),
            ChatMessage("user", "请重新规划剩余步骤")
        )

        val response = llmClient.chatStream(messages)
        return parseSteps(response.content)
    }

    /**
     * 解析步骤列表。
     * 支持多种格式：
     * 1. 描述 | !命令
     * 2. !命令（无描述）
     * 3. 编号. 描述 !命令
     */
    private fun parseSteps(content: String): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()

        // 先尝试从思考内容中提取命令
        val commandPattern = Regex("""!(\w+)\([^)]*\)""")
        val commands = commandPattern.findAll(content).map { it.value }.toList()

        if (commands.isNotEmpty()) {
            // 找到命令了，为每个命令生成描述
            for (cmd in commands) {
                val toolName = Regex("""!(\w+)""").find(cmd)?.groupValues?.get(1) ?: "执行"
                val description = getToolDescription(toolName)
                steps.add(TaskStep(description, cmd))
            }
            return steps
        }

        // 尝试 "描述 | !命令" 格式
        val lines = content.lines().filter { it.isNotBlank() }
        for (line in lines) {
            if ("|" in line) {
                val parts = line.split("|", limit = 2)
                if (parts.size == 2) {
                    val description = parts[0].trim()
                    val command = parts[1].trim()
                    if (command.startsWith("!")) {
                        steps.add(TaskStep(description, command))
                    }
                }
            } else if (line.trimStart().startsWith("!")) {
                // 纯命令格式
                val cmd = line.trim()
                val toolName = Regex("""!(\w+)""").find(cmd)?.groupValues?.get(1) ?: "执行"
                steps.add(TaskStep(getToolDescription(toolName), cmd))
            }
        }

        return steps
    }

    /**
     * 获取工具的中文描述。
     */
    private fun getToolDescription(toolName: String): String {
        return when (toolName) {
            "moveTo" -> "移动到目标位置"
            "move" -> "移动"
            "look" -> "调整视角"
            "turn" -> "转向"
            "jump" -> "跳跃"
            "attack" -> "攻击"
            "use" -> "使用物品"
            "mineBlock" -> "挖掘方块"
            "placeBlock" -> "放置方块"
            "getInventory" -> "查看背包"
            "getHealth" -> "查看血量"
            "getHunger" -> "查看饥饿值"
            "scanArea" -> "扫描周围"
            "sendMessage" -> "发送消息"
            "stop" -> "停止"
            "sneak" -> "潜行"
            else -> "执行 $toolName"
        }
    }

    /**
     * 执行单个步骤。
     */
    private suspend fun executeStep(step: TaskStep): String {
        val command = step.command

        // 解析命令
        val match = Regex("""!(\w+)\(([^)]*)\)""").find(command)
        if (match == null) {
            isFailed = true
            failReason = "无法解析命令：$command"
            return failReason
        }

        val toolName = match.groupValues[1]
        val paramsStr = match.groupValues[2]
        val params = parseParams(paramsStr)

        // 执行
        val result = actionExecutor.execute(toolName, params)

        // 检查是否失败
        val failed = result.startsWith("Failed") ||
                     result.startsWith("Error") ||
                     result.startsWith("挖掘失败") ||
                     result.startsWith("移动未完成") ||
                     result.startsWith("Bot 不存在") ||
                     result.startsWith("未知工具") ||
                     result.startsWith("无效方向")

        if (failed) {
            isFailed = true
            failReason = result
        }

        return result
    }

    /**
     * 解析参数字符串。
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
}

/**
 * 任务步骤。
 */
data class TaskStep(
    val description: String,
    val command: String,
    var result: String = "",
    var completed: Boolean = false
)
