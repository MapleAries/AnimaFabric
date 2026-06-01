package com.maple.agent

import net.minecraft.server.level.ServerPlayer

/**
 * 工具执行器 - 使用 carpet 的 /player 命令控制 bot。
 */
class ActionExecutor(private val botName: String, private val server: net.minecraft.server.MinecraftServer) {

    /**
     * 执行单个工具调用，返回执行结果。
     */
    suspend fun execute(toolName: String, params: Map<String, Any>): String {
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
     * 通过 carpet 的 /player 命令执行操作。
     */
    private fun executeCarpetCommand(command: String): Boolean {
        return try {
            val fullCommand = "/player $botName $command"
            println("[MC-Mind] 执行 carpet 命令: $fullCommand")

            val commandManager = server.getCommands()
            val source = server.createCommandSourceStack()
            commandManager.performPrefixedCommand(source, fullCommand)
            true
        } catch (e: Exception) {
            println("[MC-Mind] carpet 命令执行异常: ${e.message}")
            false
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

        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val startPos = bot.position()

        // 计算方向和距离
        val dx = x - startPos.x
        val dz = z - startPos.z
        val distance = Math.sqrt(dx * dx + dz * dz)

        // 计算目标朝向
        val targetYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()

        // 设置朝向
        executeCarpetCommand("look $targetYaw 0")

        // 开始向前移动
        executeCarpetCommand("move forward")

        // 等待移动完成（根据距离计算等待时间）
        val waitTime = (distance * 250L).toLong().coerceIn(500, 10000)
        kotlinx.coroutines.delay(waitTime)

        // 停止移动
        executeCarpetCommand("stop")

        val endPos = bot.position()
        val movedDistance = startPos.distanceTo(endPos)

        return "已移动到 ($x, $y, $z) 附近（移动了${"%.1f".format(movedDistance)}格）"
    }

    private suspend fun executeMove(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"
        val distance = getIntParam(params, "ticks") ?: 5

        // 获取 bot 当前位置
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val startPos = bot.position()

        println("[MC-Mind] Bot 位置: (${startPos.x}, ${startPos.y}, ${startPos.z}), 方向: $direction, 距离: $distance")

        // 支持多种方向格式
        val carpetDirection = when (direction.lowercase()) {
            "forward", "north", "n" -> "forward"
            "backward", "south", "s" -> "backward"
            "left", "west", "w" -> "left"
            "right", "east", "e" -> "right"
            else -> return "无效方向：$direction"
        }

        // 开始移动
        executeCarpetCommand("move $carpetDirection")
        println("[MC-Mind] 开始移动: move $carpetDirection")

        // 等待移动完成（行走速度约 4.317 格/秒，每格约 232ms）
        val waitTime = (distance * 250L).coerceAtMost(5000L) // 每格 250ms，最多 5 秒
        kotlinx.coroutines.delay(waitTime)

        // 停止移动
        executeCarpetCommand("stop")
        println("[MC-Mind] 停止移动")

        // 获取移动后的位置
        val endPos = bot.position()
        val movedDistance = startPos.distanceTo(endPos)

        return "已向 $direction 移动（约${"%.1f".format(movedDistance)}格）"
    }

    private fun executeLook(params: Map<String, Any>): String {
        val yaw = (params["yaw"] as? Number)?.toFloat() ?: return "缺少参数 yaw"
        val pitch = (params["pitch"] as? Number)?.toFloat() ?: return "缺少参数 pitch"

        executeCarpetCommand("look $yaw $pitch")

        return "视角已设置为 yaw=$yaw, pitch=$pitch"
    }

    private fun executeTurn(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"

        when (direction.lowercase()) {
            "left" -> executeCarpetCommand("turn left")
            "right" -> executeCarpetCommand("turn right")
            "back" -> executeCarpetCommand("turn back")
            else -> return "无效方向：$direction"
        }

        return "已转向 $direction"
    }

    private fun executeJump(): String {
        executeCarpetCommand("jump")
        return "已跳跃"
    }

    private suspend fun executeAttack(params: Map<String, Any>): String {
        val duration = (params["duration"] as? Number)?.toInt() ?: 1

        executeCarpetCommand("attack continuous")

        if (duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            executeCarpetCommand("stop")
        }

        return "已攻击"
    }

    private suspend fun executeUse(params: Map<String, Any>): String {
        val duration = (params["duration"] as? Number)?.toInt() ?: 1

        executeCarpetCommand("use continuous")

        if (duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            executeCarpetCommand("stop")
        }

        return "已使用物品"
    }

    private suspend fun executeMineBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        // 先看向方块
        executeCarpetCommand("look at $x $y $z")

        // 开始挖掘
        executeCarpetCommand("attack continuous")

        // 等待挖掘完成
        kotlinx.coroutines.delay(3000)

        // 停止挖掘
        executeCarpetCommand("stop")

        return "已挖掘 ($x, $y, $z)"
    }

    private fun executePlaceBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"
        val blockName = params["block"] as? String ?: return "缺少参数 block"

        // 看向放置位置
        executeCarpetCommand("look at $x $y $z")

        // 使用物品放置
        executeCarpetCommand("use")

        return "已在 ($x, $y, $z) 放置 $blockName"
    }

    private fun executeGetInventory(): String {
        // 获取 bot 玩家对象
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

    private fun executeScanArea(params: Map<String, Any>): String {
        val radius = getIntParam(params, "radius") ?: 5
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val pos = bot.blockPosition()
        val level = bot.level() as net.minecraft.server.level.ServerLevel

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
        // 使用 Minecraft 的 /say 命令而不是 carpet 的 say
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "/say [$botName] $message"
            )
        } catch (e: Exception) {
            println("[MC-Mind] 发送消息失败: ${e.message}")
        }
        return "已发送消息"
    }

    private fun executeStop(): String {
        executeCarpetCommand("stop")
        return "已停止所有动作"
    }
}
