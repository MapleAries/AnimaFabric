package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.server.MinecraftServer

/**
 * 简单指令执行器 - 直接执行，不经过 LLM。
 */
class SimpleCommandExecutor(
    private val botName: String,
    private val server: MinecraftServer
) {

    /**
     * 执行简单指令。
     */
    suspend fun execute(action: String, groups: List<String>): String {
        return when (action) {
            "move_forward" -> executeMove("forward", groups)
            "move_backward" -> executeMove("backward", groups)
            "move_left" -> executeMove("left", groups)
            "move_right" -> executeMove("right", groups)
            "move_to" -> executeMoveTo(groups)
            "turn_left" -> executeTurn("left")
            "turn_right" -> executeTurn("right")
            "turn_back" -> executeTurn("back")
            "jump" -> executeJump()
            "attack" -> executeAttack()
            "use" -> executeUse()
            "get_inventory" -> executeGetInventory()
            "get_health" -> executeGetHealth()
            "get_hunger" -> executeGetHunger()
            "scan_area" -> executeScanArea()
            "stop" -> executeStop()
            "mine_front" -> executeMineFront()
            else -> "未知指令: $action"
        }
    }

    private suspend fun executeMove(direction: String, groups: List<String>): String {
        // 从 groups 中提取距离，默认 1 格
        val distance = groups.getOrNull(2)?.toIntOrNull() ?: 1

        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val startPos = bot.position()

        // carpet 的 move 命令格式
        executeCarpetCommand("move $direction")

        // 等待移动完成（行走速度约 4.317 格/秒，每格约 232ms）
        val waitTime = (distance * 250L).coerceAtMost(5000L)
        kotlinx.coroutines.delay(waitTime)

        // 停止移动
        executeCarpetCommand("stop")

        val endPos = bot.position()
        val movedDistance = startPos.distanceTo(endPos)

        return "已向 $direction 移动约${"%.1f".format(movedDistance)}格"
    }

    private suspend fun executeMoveTo(groups: List<String>): String {
        if (groups.size < 4) return "坐标格式错误"
        val x = groups[1].toIntOrNull() ?: return "X 坐标无效"
        val y = groups[2].toIntOrNull() ?: return "Y 坐标无效"
        val z = groups[3].toIntOrNull() ?: return "Z 坐标无效"

        executeCarpetCommand("move forward")
        kotlinx.coroutines.delay(2000)
        executeCarpetCommand("stop")

        return "正在移动到 ($x, $y, $z)"
    }

    private fun executeTurn(direction: String): String {
        executeCarpetCommand("turn $direction")
        return "已转向 $direction"
    }

    private fun executeJump(): String {
        executeCarpetCommand("jump")
        return "已跳跃"
    }

    private suspend fun executeAttack(): String {
        executeCarpetCommand("attack continuous")
        kotlinx.coroutines.delay(500)
        executeCarpetCommand("stop")
        return "已攻击"
    }

    private suspend fun executeUse(): String {
        executeCarpetCommand("use continuous")
        kotlinx.coroutines.delay(500)
        executeCarpetCommand("stop")
        return "已使用物品"
    }

    private fun executeGetInventory(): String {
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val items = mutableListOf<String>()
        for (i in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(i)
            if (!stack.isEmpty) {
                items.add("${stack.hoverName.string} x${stack.count}")
            }
        }
        return if (items.isEmpty()) "背包为空" else items.joinToString("\n")
    }

    private fun executeGetHealth(): String {
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        return "血量：${bot.health.toInt()}/${bot.maxHealth.toInt()}"
    }

    private fun executeGetHunger(): String {
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        return "饥饿值：${bot.foodData.foodLevel}/20"
    }

    private fun executeScanArea(): String {
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val pos = bot.blockPosition()
        val level = bot.level() as net.minecraft.server.level.ServerLevel

        val blockCounts = mutableMapOf<String, Int>()
        for (dx in -5..5) {
            for (dy in -5..5) {
                for (dz in -5..5) {
                    val bp = pos.offset(dx, dy, dz)
                    val state = level.getBlockState(bp)
                    if (!state.isAir) {
                        val name = state.block.name.string
                        blockCounts[name] = (blockCounts[name] ?: 0) + 1
                    }
                }
            }
        }

        return if (blockCounts.isEmpty()) {
            "半径 5 格内没有方块"
        } else {
            blockCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString("\n") { "${it.key} x${it.value}" }
        }
    }

    private fun executeStop(): String {
        executeCarpetCommand("stop")
        return "已停止所有动作"
    }

    /**
     * 挖掘面前的方块 - 使用世界感知获取面前方块坐标。
     */
    private suspend fun executeMineFront(): String {
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val pos = bot.blockPosition()
        val level = bot.level() as net.minecraft.server.level.ServerLevel

        // 根据朝向获取面前 1 格的方块
        val yaw = bot.yRot
        val facingDirection = getFacingDirection(yaw)
        val frontPos = when (facingDirection) {
            "NORTH" -> pos.north()
            "SOUTH" -> pos.south()
            "EAST" -> pos.east()
            "WEST" -> pos.west()
            else -> pos
        }

        val blockState = level.getBlockState(frontPos)
        if (blockState.isAir) {
            return "面前没有方块"
        }

        val blockName = blockState.block.name.string

        // 看向方块
        executeCarpetCommand("look at ${frontPos.x} ${frontPos.y} ${frontPos.z}")

        // 开始挖掘
        executeCarpetCommand("attack continuous")

        // 等待挖掘完成（根据方块硬度调整）
        kotlinx.coroutines.delay(2000)

        // 停止挖掘
        executeCarpetCommand("stop")

        return "已挖掘面前的 $blockName (${frontPos.x}, ${frontPos.y}, ${frontPos.z})"
    }

    private fun getFacingDirection(yaw: Float): String {
        val normalizedYaw = ((yaw % 360) + 360) % 360
        return when {
            normalizedYaw >= 315 || normalizedYaw < 45 -> "SOUTH"
            normalizedYaw >= 45 && normalizedYaw < 135 -> "WEST"
            normalizedYaw >= 135 && normalizedYaw < 225 -> "NORTH"
            else -> "EAST"
        }
    }

    private fun executeCarpetCommand(command: String): Boolean {
        return try {
            val fullCommand = "/player $botName $command"
            println("[MC-Mind] 执行简单指令: $fullCommand")
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), fullCommand)
            true
        } catch (e: Exception) {
            println("[MC-Mind] 指令执行异常: ${e.message}")
            false
        }
    }
}
