package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer

/**
 * 简单指令执行器 - 直接执行，不经过 LLM。
 * 直接操作 FakePlayer 的 ActionPack。
 */
class SimpleCommandExecutor(
    private val botName: String,
    private val server: MinecraftServer
) {

    private fun getFakePlayer() = FakePlayerManager.getFakePlayer(botName)
    private fun getServerPlayer() = FakePlayerManager.getBot(server, botName)

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
            "sneak" -> executeSneak()
            "mine_front" -> executeMineFront()
            "execute_command" -> executeDirectCommand(groups)
            else -> "未知指令: $action"
        }
    }

    private suspend fun executeMove(direction: String, groups: List<String>): String {
        val distance = groups.getOrNull(2)?.toIntOrNull() ?: 1

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        val startPos = fakePlayer.position()

        val (fwd, strafe) = when (direction.lowercase()) {
            "forward" -> 1.0f to 0.0f
            "backward" -> -1.0f to 0.0f
            "left" -> 0.0f to -1.0f
            "right" -> 0.0f to 1.0f
            else -> return "无效方向：$direction"
        }

        fakePlayer.actionPack.setMovement(fwd, strafe)

        val waitTime = (distance * 250L).coerceAtMost(5000L)
        kotlinx.coroutines.delay(waitTime)

        fakePlayer.actionPack.stopMovement()

        val endPos = fakePlayer.position()
        val movedDistance = startPos.distanceTo(endPos)
        return "已向 $direction 移动约${"%.1f".format(movedDistance)}格"
    }

    private suspend fun executeMoveTo(groups: List<String>): String {
        if (groups.size < 4) return "坐标格式错误"
        val x = groups[1].toIntOrNull() ?: return "X 坐标无效"
        val y = groups[2].toIntOrNull() ?: return "Y 坐标无效"
        val z = groups[3].toIntOrNull() ?: return "Z 坐标无效"

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        val startPos = fakePlayer.blockPosition()
        val targetPos = BlockPos(x, y, z)

        // 使用 A* 寻路
        val level = fakePlayer.level()
        val path = com.maple.pathfinding.AStarPathfinder.findPath(level, startPos, targetPos)

        if (path.isEmpty()) {
            // fallback 到直线移动
            val dx = x - startPos.x.toDouble()
            val dz = z - startPos.z.toDouble()
            val yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            fakePlayer.actionPack.lookAt(fakePlayer, yaw, 0f)
            fakePlayer.actionPack.setMovement(1.0f, 0f)
            kotlinx.coroutines.delay(2000)
            fakePlayer.actionPack.stopAll()
            return "正在移动到 ($x, $y, $z)（无路径，直线移动）"
        }

        // 使用 PathFollower
        val pathFollower = com.maple.pathfinding.PathFollower()
        pathFollower.setPath(path)

        var ticks = 0
        while (!pathFollower.isComplete() && !pathFollower.isFailed() && ticks < 400) {
            pathFollower.tick(fakePlayer)
            kotlinx.coroutines.delay(50)
            ticks++
        }

        fakePlayer.actionPack.stopAll()
        return "已移动到 ($x, $y, $z)"
    }

    private fun executeTurn(direction: String): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.turn(fakePlayer, direction)
        return "已转向 $direction"
    }

    private fun executeJump(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.start(ActionPack.ActionType.JUMP, ActionPack.Action.once(ActionPack.ActionType.JUMP))
        return "已跳跃"
    }

    private suspend fun executeAttack(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.ATTACK)
        kotlinx.coroutines.delay(500)
        fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.ATTACK)
        return "已攻击"
    }

    private suspend fun executeUse(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.USE)
        kotlinx.coroutines.delay(500)
        fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.USE)
        return "已使用物品"
    }

    private fun executeGetInventory(): String {
        val bot = getServerPlayer() ?: return "Bot 不存在"
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
        val bot = getServerPlayer() ?: return "Bot 不存在"
        return "血量：${bot.health.toInt()}/${bot.maxHealth.toInt()}"
    }

    private fun executeGetHunger(): String {
        val bot = getServerPlayer() ?: return "Bot 不存在"
        return "饥饿值：${bot.foodData.foodLevel}/20"
    }

    private fun executeScanArea(): String {
        val bot = getServerPlayer() ?: return "Bot 不存在"
        val pos = bot.blockPosition()
        val level = bot.level()

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
        val fakePlayer = getFakePlayer()
        fakePlayer?.actionPack?.stopAll()
        return "已停止所有动作"
    }

    private fun executeSneak(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.sneaking = !fakePlayer.actionPack.sneaking
        return if (fakePlayer.actionPack.sneaking) "已蹲下" else "已站起"
    }

    /**
     * 挖掘面前的方块。
     */
    private suspend fun executeMineFront(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        val pos = fakePlayer.blockPosition()
        val level = fakePlayer.level()

        // 根据朝向获取面前 1 格的方块
        val yaw = fakePlayer.yRot
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

        // 看向方块并开始挖掘
        fakePlayer.actionPack.lookAtBlock(fakePlayer, frontPos)
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.ATTACK)

        // 循环检测，直到方块被破坏或超时
        var ticks = 0
        val maxTicks = 100
        while (ticks < maxTicks) {
            if (level.getBlockState(frontPos).isAir) break
            kotlinx.coroutines.delay(50)
            ticks++
        }

        fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.ATTACK)

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

    /**
     * 执行直接指令（如 /give, /tp 等）。
     */
    private fun executeDirectCommand(groups: List<String>): String {
        val command = groups.getOrElse(1) { return "指令为空" }

        return try {
            val fullCommand = if (command.startsWith("/")) command else "/$command"
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                fullCommand
            )
            "已执行指令: $fullCommand"
        } catch (e: Exception) {
            "指令执行失败: ${e.message}"
        }
    }
}
