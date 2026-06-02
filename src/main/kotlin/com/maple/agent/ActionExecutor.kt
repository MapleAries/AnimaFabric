package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.core.BlockPos

/**
 * 工具执行器 - 直接操作 FakePlayer 的 ActionPack，不依赖外部 mod。
 */
class ActionExecutor(private val botName: String, private val server: net.minecraft.server.MinecraftServer) {

    /**
     * 获取 FakePlayer 实例。
     */
    private fun getFakePlayer() = FakePlayerManager.getFakePlayer(botName)

    /**
     * 获取 ServerPlayer（兼容非 FakePlayer 的情况）。
     */
    private fun getServerPlayer() = FakePlayerManager.getBot(server, botName)

    /**
     * 执行单个工具调用，返回执行结果。
     */
    suspend fun execute(toolName: String, params: Map<String, Any>): String {
        // 将位置参数（param0, param1...）映射为命名参数
        val resolvedParams = resolvePositionalParams(toolName, params)
        return executeInternal(toolName, resolvedParams)
    }

    /**
     * 将位置参数映射为命名参数。
     * 例如 move 工具：param0 -> direction, param1 -> ticks
     */
    private fun resolvePositionalParams(toolName: String, params: Map<String, Any>): Map<String, Any> {
        // 如果已经有命名参数，直接返回
        if (params.keys.none { it.startsWith("param") }) return params

        val tool = ToolRegistry.getTool(toolName) ?: return params
        val resolved = mutableMapOf<String, Any>()

        for ((key, value) in params) {
            if (key.startsWith("param")) {
                val index = key.removePrefix("param").toIntOrNull() ?: continue
                if (index < tool.parameters.size) {
                    resolved[tool.parameters[index].name] = value
                }
            } else {
                resolved[key] = value
            }
        }

        return resolved
    }

    private suspend fun executeInternal(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "moveTo" -> executeMoveTo(params)
            "move" -> executeMove(params)
            "look" -> executeLook(params)
            "turn" -> executeTurn(params)
            "jump" -> executeJump()
            "attack" -> executeAttack(params)
            "use" -> executeUse(params)
            "mineBlock" -> executeMineBlock(params)
            "placeBlock" -> executePlaceBlock(params)
            "getInventory" -> executeGetInventory()
            "getHealth" -> executeGetHealth()
            "getHunger" -> executeGetHunger()
            "scanArea" -> executeScanArea(params)
            "sendMessage" -> executeSendMessage(params)
            "stop" -> executeStop()
            else -> "未知工具：$toolName"
        }
    }

    /**
     * 从参数中获取整数值，支持 Number 和 String 类型。
     */
    private fun getIntParam(params: Map<String, Any>, key: String): Int? {
        val value = params[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private suspend fun executeMoveTo(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        val startPos = fakePlayer.position()
        val targetPos = BlockPos(x, y, z)

        // 使用 A* 寻路
        val level = fakePlayer.level()
        val path = com.maple.pathfinding.AStarPathfinder.findPath(level, fakePlayer.blockPosition(), targetPos)

        if (path.isEmpty()) {
            // A* 找不到路径，fallback 到直线移动
            val dx = x - startPos.x
            val dz = z - startPos.z
            val distance = Math.sqrt(dx * dx + dz * dz)
            val targetYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()

            fakePlayer.actionPack.lookAt(fakePlayer, targetYaw, 0f)
            fakePlayer.actionPack.setMovement(1.0f, 0f, distance > 10)
            val waitTime = (distance * 250L).toLong().coerceIn(500, 10000)
            kotlinx.coroutines.delay(waitTime)
            fakePlayer.actionPack.stopAll()

            val endPos = fakePlayer.position()
            val movedDistance = startPos.distanceTo(endPos)
            return "已移动到 ($x, $y, $z) 附近（移动了${"%.1f".format(movedDistance)}格，无路径）"
        }

        // 使用 PathFollower 沿 A* 路径移动
        val pathFollower = com.maple.pathfinding.PathFollower()
        pathFollower.setPath(path)

        var ticks = 0
        val maxTicks = 400 // 最多 20 秒
        while (!pathFollower.isComplete() && !pathFollower.isFailed() && ticks < maxTicks) {
            pathFollower.tick(fakePlayer)
            kotlinx.coroutines.delay(50) // 一个 tick
            ticks++
        }

        fakePlayer.actionPack.stopAll()

        val endPos = fakePlayer.position()
        val movedDistance = startPos.distanceTo(endPos)
        return if (pathFollower.isComplete()) {
            "已移动到 ($x, $y, $z)（移动了${"%.1f".format(movedDistance)}格）"
        } else {
            "移动未完成，到达附近（移动了${"%.1f".format(movedDistance)}格）"
        }
    }

    private suspend fun executeMove(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"
        val distance = getIntParam(params, "ticks") ?: 5

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        val startPos = fakePlayer.position()

        // 根据方向设置移动输入
        val (fwd, strafe) = when (direction.lowercase()) {
            "forward", "north", "n" -> 1.0f to 0.0f
            "backward", "south", "s" -> -1.0f to 0.0f
            "left", "west", "w" -> 0.0f to -1.0f
            "right", "east", "e" -> 0.0f to 1.0f
            else -> return "无效方向：$direction"
        }

        fakePlayer.actionPack.setMovement(fwd, strafe)

        val waitTime = (distance * 250L).coerceAtMost(5000L)
        kotlinx.coroutines.delay(waitTime)

        fakePlayer.actionPack.stopMovement()

        val endPos = fakePlayer.position()
        val movedDistance = startPos.distanceTo(endPos)
        return "已向 $direction 移动（约${"%.1f".format(movedDistance)}格）"
    }

    private fun executeLook(params: Map<String, Any>): String {
        val yaw = (params["yaw"] as? Number)?.toFloat() ?: return "缺少参数 yaw"
        val pitch = (params["pitch"] as? Number)?.toFloat() ?: return "缺少参数 pitch"

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.lookAt(fakePlayer, yaw, pitch)

        return "视角已设置为 yaw=$yaw, pitch=$pitch"
    }

    private fun executeTurn(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.turn(fakePlayer, direction)

        return "已转向 $direction"
    }

    private fun executeJump(): String {
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.start(ActionPack.ActionType.JUMP, ActionPack.Action.once(ActionPack.ActionType.JUMP))
        return "已跳跃"
    }

    private suspend fun executeAttack(params: Map<String, Any>): String {
        val duration = (params["duration"] as? Number)?.toInt() ?: 1

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.ATTACK)

        if (duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.ATTACK)
        }

        return "已攻击"
    }

    private suspend fun executeUse(params: Map<String, Any>): String {
        val duration = (params["duration"] as? Number)?.toInt() ?: 1

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.USE)

        if (duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.USE)
        }

        return "已使用物品"
    }

    private suspend fun executeMineBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val targetPos = BlockPos(x, y, z)
        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"

        // 1. 安全预校验：检查距离和视线阻挡
        val eyePos = fakePlayer.eyePosition
        val blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(targetPos)
        val distance = eyePos.distanceTo(blockCenter)

        if (distance > 5.0) {
            return "挖掘失败：方块太远（${"%.2f".format(distance)}格，最大 5.0 格），请先靠近。"
        }

        // 射线检测：看向方块后检查是否能看到
        val originalYaw = fakePlayer.yRot
        val originalPitch = fakePlayer.xRot
        fakePlayer.actionPack.lookAtBlock(fakePlayer, targetPos)

        val hitResult = fakePlayer.pick(5.0, 1.0f, false)
        val isSafe = if (hitResult.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            val blockHit = hitResult as net.minecraft.world.phys.BlockHitResult
            blockHit.blockPos == targetPos
        } else {
            false
        }

        if (!isSafe) {
            // 还原视角
            fakePlayer.yRot = originalYaw
            fakePlayer.xRot = originalPitch
            return "挖掘失败：方块 ($x, $y, $z) 视线被阻挡，请先移动到附近。"
        }

        // 2. 开始挖掘（通过 ActionPack 的持续攻击，它内置了方块破坏状态机）
        fakePlayer.actionPack.startContinuous(ActionPack.ActionType.ATTACK)

        // 3. 动态监测方块状态，一旦破坏立即停止
        var ticks = 0
        val maxTicks = 100 // 最多等待 5 秒
        while (ticks < maxTicks) {
            if (fakePlayer.level().getBlockState(targetPos).isAir) break
            kotlinx.coroutines.delay(50)
            ticks++
        }

        // 停止挖掘
        fakePlayer.actionPack.stopContinuous(ActionPack.ActionType.ATTACK)

        return "已挖掘 ($x, $y, $z)"
    }

    private fun executePlaceBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"
        val blockName = params["block"] as? String ?: return "缺少参数 block"

        val fakePlayer = getFakePlayer() ?: return "Bot 不存在或不是 FakePlayer"

        // 看向放置位置
        fakePlayer.actionPack.lookAtBlock(fakePlayer, BlockPos(x, y, z))

        // 使用物品（放置）
        fakePlayer.actionPack.start(ActionPack.ActionType.USE, ActionPack.Action.once(ActionPack.ActionType.USE))

        return "已在 ($x, $y, $z) 放置 $blockName"
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

    private fun executeScanArea(params: Map<String, Any>): String {
        val radius = getIntParam(params, "radius") ?: 5
        val bot = getServerPlayer() ?: return "Bot 不存在"
        val pos = bot.blockPosition()
        val level = bot.level()

        val blockCounts = mutableMapOf<String, Int>()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
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
            "半径 ${radius} 格内没有方块"
        } else {
            blockCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString("\n") { "${it.key} x${it.value}" }
        }
    }

    private fun executeSendMessage(params: Map<String, Any>): String {
        val message = params["message"] as? String ?: return "缺少参数 message"
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "/say [$botName] $message"
            )
        } catch (e: Exception) {
            println("[AnimaFabric] 发送消息失败: ${e.message}")
        }
        return "已发送消息"
    }

    private fun executeStop(): String {
        val fakePlayer = getFakePlayer()
        if (fakePlayer != null) {
            fakePlayer.actionPack.stopAll()
        }
        return "已停止所有动作"
    }
}
