package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.world.item.Item

/**
 * 结构化行动方案执行器。
 * 支持顺序执行、循环、条件分支等控制流。
 * 替代简单的线性命令执行，实现 Voyager 风格的代码生成。
 */
class PlanExecutor(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val actionExecutor: ActionExecutor
) {
    private val results = mutableListOf<StepResult>()

    /**
     * 执行一个行动方案节点。
     */
    suspend fun execute(node: ActionPlanNode): List<StepResult> {
        results.clear()
        executeNode(node)
        return results.toList()
    }

    private suspend fun executeNode(node: ActionPlanNode) {
        when (node) {
            is ActionPlanNode.Action -> executeAction(node)
            is ActionPlanNode.Sequential -> {
                for (step in node.steps) {
                    executeNode(step)
                    // 如果最后一步失败，停止执行
                    if (results.lastOrNull()?.failed == true) break
                }
            }
            is ActionPlanNode.Loop -> executeLoop(node)
            is ActionPlanNode.Conditional -> executeConditional(node)
            is ActionPlanNode.Delay -> kotlinx.coroutines.delay(node.ms)
        }
    }

    private suspend fun executeAction(node: ActionPlanNode.Action) {
        val result = actionExecutor.execute(node.tool, node.params)
        val failed = result.startsWith("Failed") ||
                     result.startsWith("Error") ||
                     result.startsWith("挖掘失败") ||
                     result.startsWith("放置失败") ||
                     result.startsWith("移动未完成") ||
                     result.startsWith("Bot 不存在") ||
                     result.startsWith("未知工具")
        results.add(StepResult(node.tool, result, failed))
    }

    private suspend fun executeLoop(node: ActionPlanNode.Loop) {
        var iterations = 0
        val maxIter = node.maxIterations

        while (iterations < maxIter) {
            // 检查 while 条件（如果存在）
            if (node.whileCondition != null) {
                if (!checkLoopCondition(node.whileCondition)) break
            }

            // 执行循环体
            for (step in node.steps) {
                executeNode(step)
                if (results.lastOrNull()?.failed == true) {
                    println("[AnimaFabric] Loop iteration $iterations: step failed, stopping loop")
                    return
                }
            }

            iterations++

            // 检查 until 条件（如果存在）
            if (node.until != null) {
                if (checkLoopCondition(node.until)) {
                    println("[AnimaFabric] Loop completed after $iterations iterations (until condition met)")
                    return
                }
            }

            // 每次循环之间短暂延迟，避免卡死
            kotlinx.coroutines.delay(100)
        }

        if (iterations >= maxIter) {
            println("[AnimaFabric] Loop reached max iterations ($maxIter)")
            results.add(StepResult("loop", "循环达到最大迭代次数 $maxIter", false))
        }
    }

    private suspend fun executeConditional(node: ActionPlanNode.Conditional) {
        val conditionMet = checkCondition(node.condition)

        val stepsToExecute = if (conditionMet) node.thenSteps else node.elseSteps
        for (step in stepsToExecute) {
            executeNode(step)
            if (results.lastOrNull()?.failed == true) break
        }
    }

    /**
     * 检查条件是否满足。
     */
    private suspend fun checkCondition(condition: CheckCondition): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false

            when (condition.type) {
                ConditionType.INVENTORY_CONTAINS -> {
                    val itemName = condition.item ?: return@runOnGameThread false
                    val requiredCount = condition.count ?: 1
                    val currentCount = countItemInInventory(bot, itemName)
                    currentCount >= requiredCount
                }
                ConditionType.INVENTORY_FULL -> {
                    // 检查是否有空槽位
                    var emptySlots = 0
                    for (i in 0 until bot.inventory.containerSize) {
                        if (bot.inventory.getItem(i).isEmpty) emptySlots++
                    }
                    emptySlots == 0
                }
                ConditionType.BLOCK_AT -> {
                    val pos = condition.pos ?: return@runOnGameThread false
                    val level = bot.level()
                    val blockState = level.getBlockState(net.minecraft.core.BlockPos(pos.x, pos.y, pos.z))
                    when (condition.blockState) {
                        "air" -> blockState.isAir
                        "solid" -> blockState.isSolidRender
                        else -> blockState.block.name.string.equals(condition.blockState, ignoreCase = true)
                    }
                }
                ConditionType.HEALTH_BELOW -> {
                    val threshold = condition.health ?: return@runOnGameThread false
                    bot.health < threshold
                }
                ConditionType.HEALTH_ABOVE -> {
                    val threshold = condition.health ?: return@runOnGameThread false
                    bot.health > threshold
                }
                ConditionType.ALWAYS -> true
                ConditionType.NEVER -> false
            }
        }
    }

    /**
     * 检查 LoopCondition（与 CheckCondition 结构相同，转换后复用逻辑）。
     */
    private suspend fun checkLoopCondition(condition: LoopCondition): Boolean {
        return checkCondition(CheckCondition(
            type = condition.type,
            item = condition.item,
            count = condition.count,
            pos = condition.pos,
            blockState = condition.blockState,
            health = condition.health
        ))
    }

    /**
     * 统计背包中指定物品的数量。
     */
    private fun countItemInInventory(player: net.minecraft.server.level.ServerPlayer, itemName: String): Int {
        var count = 0
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (!stack.isEmpty) {
                val registryName = Item.getId(stack.item).toString()
                val displayName = stack.hoverName.string.lowercase()
                if (registryName.contains(itemName, ignoreCase = true) ||
                    displayName.contains(itemName, ignoreCase = true)) {
                    count += stack.count
                }
            }
        }
        return count
    }

    data class StepResult(
        val toolName: String,
        val message: String,
        val failed: Boolean
    )
}
