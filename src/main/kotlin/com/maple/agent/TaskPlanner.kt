package com.maple.agent

import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import java.nio.file.Path

/**
 * 通用复杂任务处理器（文件化版本）。
 *
 * 核心流程：
 * 1. 接收自然语言指令
 * 2. LLM 分解为步骤序列
 * 3. 写入 JSON 文件（可人工审查/编辑）
 * 4. 逐步执行，实时更新文件进度
 * 5. 失败时自适应重新规划
 * 6. 支持暂停/恢复/崩溃恢复
 */
class TaskPlanner(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val llmClient: LLMClient,
    private val actionExecutor: ActionExecutor
) {
    private var currentPlan: TaskPlan? = null
    private var currentPlanPath: Path? = null

    var isComplete = false
        private set
    var isFailed = false
        private set
    var failReason = ""
        private set

    /**
     * 处理一个复杂任务。
     * 1. 分解任务 → 写入文件
     * 2. 逐步执行 → 实时更新文件
     */
    suspend fun processTask(command: String): String {
        // 1. 分解任务
        val decomposed = decomposeTask(command)
        if (decomposed.isEmpty()) {
            // fallback 到 PipelineExecutor
            println("[AnimaFabric] TaskPlanner 分解失败，fallback 到 PipelineExecutor")
            val memory = ConversationMemory(10, llmClient)
            val pipeline = PipelineExecutor(botName, server, llmClient, memory, actionExecutor)
            return pipeline.processCommand(command)
        }

        // 2. 创建计划并写入文件
        val plan = TaskPlanManager.createPlan(command, decomposed)
        val planPath = TaskPlanManager.save(plan)
        currentPlan = plan
        currentPlanPath = planPath

        // 3. 执行计划
        return executePlan(plan, planPath)
    }

    /**
     * 恢复执行未完成的计划。
     */
    suspend fun resumePlan(planPath: Path): String {
        val plan = TaskPlanManager.load(planPath)
            ?: return "无法加载计划文件: $planPath"

        currentPlan = plan
        currentPlanPath = planPath

        return executePlan(plan, planPath)
    }

    /**
     * 执行计划。
     */
    private suspend fun executePlan(plan: TaskPlan, planPath: Path): String {
        plan.status = PlanStatus.EXECUTING
        TaskPlanManager.update(plan, planPath)

        isComplete = false
        isFailed = false
        val results = mutableListOf<String>()
        var consecutiveFailures = 0

        // 从当前步骤开始（支持恢复）
        var startIndex = plan.currentStep

        for (i in startIndex until plan.steps.size) {
            val step = plan.steps[i]
            plan.currentStep = i

            // 跳过已完成的步骤
            if (step.status == StepStatus.DONE) {
                results.add("步骤 ${step.id}: ${step.description} → ${step.result}（已完成）")
                continue
            }

            // 更新步骤状态
            step.status = StepStatus.EXECUTING
            TaskPlanManager.update(plan, planPath)

            println("[AnimaFabric] 执行步骤 ${step.id}/${plan.steps.size}: ${step.description}")

            // 执行
            val result = executeStepCommand(step.command)
            step.result = result

            val failed = isResultFailed(result)

            if (failed) {
                step.status = StepStatus.FAILED
                consecutiveFailures++
                isFailed = true
                failReason = result

                if (consecutiveFailures >= 2) {
                    // 连续失败，尝试重新规划
                    println("[AnimaFabric] 连续失败，尝试重新规划...")
                    val replanned = replanRemaining(plan.task, results)
                    if (replanned.isNotEmpty()) {
                        // 替换剩余步骤
                        val newSteps = plan.steps.subList(0, i).toMutableList()
                        replanned.forEachIndexed { idx, ts ->
                            newSteps.add(PlanStep(
                                id = newSteps.size + 1,
                                description = ts.description,
                                command = ts.command
                            ))
                        }
                        plan.steps.clear()
                        plan.steps.addAll(newSteps)
                        plan.currentStep = i
                        isFailed = false
                        consecutiveFailures = 0
                        TaskPlanManager.update(plan, planPath)
                        continue
                    }
                    break
                }

                // 单次失败，重试
                isFailed = false
                TaskPlanManager.update(plan, planPath)
                continue
            }

            // 成功
            step.status = StepStatus.DONE
            consecutiveFailures = 0
            isFailed = false
            results.add("步骤 ${step.id}: ${step.description} → $result")
            TaskPlanManager.update(plan, planPath)
        }

        // 更新最终状态
        if (!isFailed) {
            plan.status = PlanStatus.COMPLETED
            isComplete = true
        } else {
            plan.status = PlanStatus.FAILED
        }
        TaskPlanManager.update(plan, planPath)

        return results.joinToString("\n")
    }

    /**
     * 执行单个命令。
     */
    private suspend fun executeStepCommand(command: String): String {
        val match = Regex("""!(\w+)\(([^)]*)\)""").find(command)
            ?: return "无法解析命令：$command"

        val toolName = match.groupValues[1]
        val paramsStr = match.groupValues[2]
        val params = parseParams(paramsStr)

        return actionExecutor.execute(toolName, params)
    }

    /**
     * 检查执行结果是否失败。
     */
    private fun isResultFailed(result: String): Boolean {
        return result.startsWith("Failed") ||
               result.startsWith("Error") ||
               result.startsWith("挖掘失败") ||
               result.startsWith("移动未完成") ||
               result.startsWith("Bot 不存在") ||
               result.startsWith("未知工具") ||
               result.startsWith("无效方向")
    }

    /**
     * 分解任务为步骤序列。
     */
    private suspend fun decomposeTask(command: String): List<TaskStep> {
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) WorldPerception.scan(bot) else "Bot not online"

        val prompt = """将Minecraft任务分解为命令步骤。每行一个命令，只输出命令。

可用命令：
!moveTo(x,y,z) !move(dir,n) !turn(dir) !jump() !sneak()
!mineBlock(x,y,z) !placeBlock(x,y,z,block) !craft(item)
!scanArea(r) !getInventory() !attack() !use() !msg(text)

示例：
任务：挖木头做木镐
输出：
!scanArea(10)
!mineBlock(10,65,-5)
!mineBlock(10,64,-5)
!craft(planks)
!craft(crafting_table)
!craft(wooden_pickaxe)

任务：蹲下然后站起来
输出：
!sneak()
!sneak()

当前世界状态：
$worldState
"""

        val messages = listOf(
            ChatMessage("system", prompt),
            ChatMessage("user", command)
        )

        val response = llmClient.chatStream(messages)
        println("[AnimaFabric] 任务分解结果: ${response.content}")

        // 尝试从 content 提取
        val steps = parseSteps(response.content)
        if (steps.isNotEmpty()) return steps

        // fallback: 从 thinking 提取
        return parseSteps(response.thinking)
    }

    /**
     * 重新规划剩余步骤。
     */
    private suspend fun replanRemaining(originalTask: String, completedResults: List<String>): List<TaskStep> {
        val bot = server.playerList.getPlayerByName(botName)
        val worldState = if (bot != null) WorldPerception.scan(bot) else "Bot not online"

        val completed = completedResults.joinToString("\n")

        val prompt = """重新规划Minecraft任务。每行一个命令。

原始任务：$originalTask
已完成：$completed
当前状态：$worldState

可用命令：!moveTo(x,y,z) !move(dir,n) !mineBlock(x,y,z) !craft(item) !scanArea(r) !getInventory() !use() !msg(text)
"""

        val messages = listOf(
            ChatMessage("system", prompt),
            ChatMessage("user", "继续执行剩余步骤")
        )

        val response = llmClient.chatStream(messages)
        println("[AnimaFabric] 重规划结果: content=${response.content.take(200)}")

        val steps = parseSteps(response.content)
        if (steps.isNotEmpty()) return steps
        return parseSteps(response.thinking)
    }

    /**
     * 解析步骤列表。
     */
    private fun parseSteps(content: String): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        val commandPattern = Regex("""!(\w+)\([^)]*\)""")
        val typePattern = Regex("""\w+:\s*(string|number|boolean)""")
        val nameOnlyPattern = Regex("""^[a-zA-Z_]+$""")

        val commands = commandPattern.findAll(content)
            .filter { match ->
                val args = match.groupValues.getOrElse(2) { "" }.trim()
                if (args.isEmpty()) return@filter true
                if (typePattern.containsMatchIn(args)) return@filter false
                val parts = args.split(",").map { it.trim() }
                val allNames = parts.all { it.isNotEmpty() && nameOnlyPattern.matches(it) }
                !allNames
            }
            .map { it.value }
            .toList()

        for (cmd in commands) {
            val toolName = Regex("""!(\w+)""").find(cmd)?.groupValues?.get(1) ?: "执行"
            steps.add(TaskStep(getToolDescription(toolName), cmd))
        }

        return steps
    }

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
            "craft" -> "合成物品"
            else -> "执行 $toolName"
        }
    }

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
 * 任务步骤（用于 LLM 分解结果）。
 */
data class TaskStep(
    val description: String,
    val command: String
)
