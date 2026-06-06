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
    private val actionExecutor: ActionExecutor,
    private val commandSender: net.minecraft.server.level.ServerPlayer? = null
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
     * 发送聊天消息。
     */
    private fun sendChat(message: String) {
        server.playerList.broadcastSystemMessage(
            net.minecraft.network.chat.Component.literal("[$botName] $message"),
            false
        )
    }

    /**
     * 处理一个复杂任务。
     * 1. 分解任务 → 写入文件
     * 2. 逐步执行 → 实时更新文件
     */
    suspend fun processTask(command: String): String {
        // 1. 分解任务（代词上下文在 prompt 中处理）
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

        sendChat("开始执行任务：${plan.task.take(30)}（共 ${plan.steps.size} 步）")

        // 从当前步骤开始（支持恢复）
        var i = plan.currentStep

        while (i < plan.steps.size) {
            val step = plan.steps[i]
            plan.currentStep = i

            // 跳过已完成的步骤
            if (step.status == StepStatus.DONE) {
                results.add("步骤 ${step.id}: ${step.description} → ${step.result}（已完成）")
                i++
                continue
            }

            // 更新步骤状态
            step.status = StepStatus.EXECUTING
            TaskPlanManager.update(plan, planPath)

            sendChat("⏳ 步骤 ${step.id}/${plan.steps.size}：${step.description}")
            println("[AnimaFabric] 执行步骤 ${step.id}/${plan.steps.size}: ${step.description}")

            // 执行
            val result = executeStepCommand(step.command)
            step.result = result

            val failed = isResultFailed(result)

            if (failed) {
                step.status = StepStatus.FAILED
                sendChat("❌ 步骤 ${step.id} 失败：$result")
                consecutiveFailures++
                isFailed = true
                failReason = result

                if (consecutiveFailures >= 2) {
                    // 连续失败，尝试重新规划
                    sendChat("⚠️ 连续失败，正在重新规划...")
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

                // 单次失败，重试当前步骤
                isFailed = false
                step.status = StepStatus.PENDING
                TaskPlanManager.update(plan, planPath)
                continue
            }

            // 成功
            step.status = StepStatus.DONE
            consecutiveFailures = 0
            isFailed = false
            results.add("步骤 ${step.id}: ${step.description} → $result")
            sendChat("✅ 步骤 ${step.id} 完成：$result")
            TaskPlanManager.update(plan, planPath)
            i++
        }

        // 更新最终状态
        if (!isFailed) {
            plan.status = PlanStatus.COMPLETED
            isComplete = true
            sendChat("🎉 任务完成！共 ${plan.steps.size} 步")
        } else {
            plan.status = PlanStatus.FAILED
            sendChat("❌ 任务失败：$failReason")
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
        return ActionResultClassifier.isFailure(result)
    }

    /**
     * 构建代词上下文。
     * 根据指令中的代词（我/你）确定坐标来源。
     */
    private suspend fun buildPronounContext(command: String): String {
        val hasWo = command.contains("我")    // 我 = 指令发送者
        val hasNi = command.contains("你")    // 你 = 假人

        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName)
            val botPos = bot?.blockPosition()
            val senderPos = commandSender?.blockPosition()

            val sb = StringBuilder()

            if (hasNi && bot != null && botPos != null) {
                val botTarget = WorldPerception.getCrosshairTargetString(bot)
                sb.appendLine("## \"你\" = 假人 $botName")
                sb.appendLine("- 假人位置：(${botPos.x}, ${botPos.y}, ${botPos.z})")
                sb.appendLine("- 假人准星目标：$botTarget")
                sb.appendLine("- \"你面前的方块\" = 假人准星目标坐标")
                sb.appendLine("- \"你脚下\" = 假人位置 Y-1")
                sb.appendLine()
            }

            if (hasWo && commandSender != null && senderPos != null) {
                val senderTarget = WorldPerception.getCrosshairTargetString(commandSender)
                sb.appendLine("## \"我\" = 指令发送者 ${commandSender.name.string}")
                sb.appendLine("- 发送者位置：(${senderPos.x}, ${senderPos.y}, ${senderPos.z})")
                sb.appendLine("- 发送者准星目标：$senderTarget")
                sb.appendLine("- \"我面前的方块\" = 发送者准星目标坐标")
                sb.appendLine("- \"我脚下\" = 发送者位置 Y-1")
                sb.appendLine("- \"到我这里来\" = 移动到发送者位置")
                sb.appendLine()
            }

            sb.toString().trimEnd()
        }
    }

    /**
     * 分解任务为步骤序列。
     */
    private suspend fun decomposeTask(command: String): List<TaskStep> {
        val worldState = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName)
            if (bot != null) WorldPerception.scan(bot, commandSender) else "Bot not online"
        }

        // 构建代词上下文
        val pronounContext = buildPronounContext(command)

        val prompt = """将Minecraft任务分解为命令步骤。每行一个命令，只输出命令。

可用命令：
!moveTo(x,y,z) !move(dir,n) !turn(dir) !jump() !sneak()
!mineBlock(x,y,z) !placeBlock(x,y,z,block) !craft(item)
!scanArea(r) !getInventory() !attack() !use() !sendMessage(text)

重要规则：
1. 每个命令只出现一次，不要重复
2. 如果需要放置方块但背包为空，先用 !craft 获取方块
3. 坐标必须来自世界状态，不要编造坐标
4. 最多 6 个步骤

$pronounContext

当前世界状态：
$worldState
"""

        val messages = listOf(
            ChatMessage("system", prompt),
            ChatMessage("user", command)
        )

        repeat(2) { attempt ->
            val response = llmClient.chatStream(messages)
            println("[AnimaFabric] 任务分解结果（尝试 ${attempt + 1}/2）: ${response.content}")

            // 尝试从 content 提取
            val steps = parseSteps(response.content)
            if (steps.isNotEmpty()) return steps

            // fallback: 从 thinking 提取
            val thinkingSteps = parseSteps(response.thinking)
            if (thinkingSteps.isNotEmpty()) return thinkingSteps

            if (attempt == 0) {
                kotlinx.coroutines.delay(500)
            }
        }

        return emptyList()
    }

    /**
     * 重新规划剩余步骤。
     */
    private suspend fun replanRemaining(originalTask: String, completedResults: List<String>): List<TaskStep> {
        val worldState = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName)
            if (bot != null) WorldPerception.scan(bot, commandSender) else "Bot not online"
        }

        val completed = completedResults.joinToString("\n")

        val prompt = """重新规划Minecraft任务。每行一个命令。

原始任务：$originalTask
已完成：$completed
当前状态：$worldState

可用命令：!moveTo(x,y,z) !move(dir,n) !mineBlock(x,y,z) !craft(item) !scanArea(r) !getInventory() !use() !sendMessage(text)
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
