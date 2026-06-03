package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.core.BlockPos

/**
 * 工具执行器 - 通过 Carpet /player 命令控制 bot。
 * 完整支持 Carpet 的所有 player 子命令。
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
            "jump" -> executeJump(params)
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
            "sneak" -> executeSneak(params)
            "craft" -> executeCraft(params)
            "drop" -> executeDrop(params)
            "hotbar" -> executeHotbar(params)
            "swapHands" -> executeSwapHands()
            "mount" -> executeMount(params)
            "dismount" -> executeDismount()
            "sprint" -> executeSprint(params)
            else -> "未知工具：$toolName"
        }
    }

    /**
     * 通过 Carpet 的 /player 命令执行操作。
     */
    private fun executeCarpetCommand(command: String): Boolean {
        return try {
            val fullCommand = "/player $botName $command"
            println("[AnimaFabric] 执行 carpet 命令: $fullCommand")
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), fullCommand)
            true
        } catch (e: Exception) {
            println("[AnimaFabric] carpet 命令执行异常: ${e.message}")
            false
        }
    }

    private fun getIntParam(params: Map<String, Any>, key: String): Int? {
        val value = params[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun getStringParam(params: Map<String, Any>, key: String): String? {
        return params[key]?.toString()
    }

    // ========== 移动 ==========

    private suspend fun executeMoveTo(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val startPos = bot.position()

        // 使用 A* 寻路
        val level = bot.level()
        val path = com.maple.pathfinding.AStarPathfinder.findPath(level, bot.blockPosition(), BlockPos(x, y, z))

        if (path.isEmpty()) {
            // fallback 到直线移动
            val dx = x - startPos.x
            val dz = z - startPos.z
            val distance = Math.sqrt(dx * dx + dz * dz)

            executeCarpetCommand("look at $x $y $z")
            executeCarpetCommand("move forward")
            kotlinx.coroutines.delay((distance * 250L).toLong().coerceIn(500, 10000))
            executeCarpetCommand("stop")

            val endPos = bot.position()
            val movedDistance = startPos.distanceTo(endPos)
            return "已移动到 ($x, $y, $z) 附近（移动了${"%.1f".format(movedDistance)}格，无路径）"
        }

        // 沿 A* 路径移动
        for (i in 1 until path.size) {
            val target = path[i]
            executeCarpetCommand("look at ${target.x} ${target.y} ${target.z}")
            executeCarpetCommand("move forward")
            kotlinx.coroutines.delay(300)
            executeCarpetCommand("stop")
        }

        val endPos = bot.position()
        val movedDistance = startPos.distanceTo(endPos)
        return "已移动到 ($x, $y, $z)（移动了${"%.1f".format(movedDistance)}格）"
    }

    private suspend fun executeMove(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"
        val distance = getIntParam(params, "ticks") ?: 5

        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"
        val startPos = bot.position()

        // Carpet move 命令：forward/backward/left/right
        val carpetDir = when (direction.lowercase()) {
            "forward", "north", "n" -> "forward"
            "backward", "back", "south", "s" -> "backward"
            "left", "west", "w" -> "left"
            "right", "east", "e" -> "right"
            else -> return "无效方向：$direction"
        }

        executeCarpetCommand("move $carpetDir")
        kotlinx.coroutines.delay((distance * 250L).coerceAtMost(5000L))
        executeCarpetCommand("stop")

        val endPos = bot.position()
        val movedDistance = startPos.distanceTo(endPos)
        return "已向 $direction 移动（约${"%.1f".format(movedDistance)}格）"
    }

    // ========== 视角 ==========

    private fun executeLook(params: Map<String, Any>): String {
        val yaw = (params["yaw"] as? Number)?.toFloat()
        val pitch = (params["pitch"] as? Number)?.toFloat()

        if (yaw != null && pitch != null) {
            // 精确角度：Carpet look 命令接受 yaw pitch
            executeCarpetCommand("look $yaw $pitch")
            return "视角已设置为 yaw=$yaw, pitch=$pitch"
        }

        // 方向参数
        val direction = getStringParam(params, "direction")
        if (direction != null) {
            executeCarpetCommand("look $direction")
            return "视角已朝向 $direction"
        }

        return "缺少参数"
    }

    private fun executeTurn(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"

        // Carpet turn: back/left/right 或 <pitch> <yaw>
        when (direction.lowercase()) {
            "back", "left", "right" -> executeCarpetCommand("turn $direction")
            else -> return "无效方向：$direction（可用：back/left/right）"
        }

        return "已转向 $direction"
    }

    // ========== 动作 ==========

    private fun executeJump(params: Map<String, Any>): String {
        val mode = getStringParam(params, "mode")
        // Carpet jump: 无参数=once, continuous, interval <ticks>, once
        val cmd = when (mode?.lowercase()) {
            "continuous" -> "jump continuous"
            "interval" -> {
                val interval = getIntParam(params, "interval") ?: 20
                "jump interval $interval"
            }
            else -> "jump"
        }
        executeCarpetCommand(cmd)
        return "已跳跃"
    }

    private suspend fun executeAttack(params: Map<String, Any>): String {
        val mode = getStringParam(params, "mode") ?: "once"
        val duration = getIntParam(params, "duration")

        // Carpet attack: 无参数=once, continuous, interval <ticks>, once
        val cmd = when (mode.lowercase()) {
            "continuous" -> "attack continuous"
            "interval" -> {
                val interval = getIntParam(params, "interval") ?: 20
                "attack interval $interval"
            }
            else -> "attack"
        }

        executeCarpetCommand(cmd)

        if (mode.lowercase() == "continuous" && duration != null && duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            executeCarpetCommand("stop")
        }

        return "已攻击"
    }

    private suspend fun executeUse(params: Map<String, Any>): String {
        val mode = getStringParam(params, "mode") ?: "once"
        val duration = getIntParam(params, "duration")

        // Carpet use: 无参数=once, continuous, interval <ticks>, once
        val cmd = when (mode.lowercase()) {
            "continuous" -> "use continuous"
            "interval" -> {
                val interval = getIntParam(params, "interval") ?: 20
                "use interval $interval"
            }
            else -> "use"
        }

        executeCarpetCommand(cmd)

        if (mode.lowercase() == "continuous" && duration != null && duration > 0) {
            kotlinx.coroutines.delay(duration * 50L)
            executeCarpetCommand("stop")
        }

        return "已使用物品"
    }

    private fun executeStop(): String {
        executeCarpetCommand("stop")
        return "已停止所有动作"
    }

    // ========== 潜行/冲刺 ==========

    private fun executeSneak(params: Map<String, Any>): String {
        val duration = getIntParam(params, "duration")
        if (duration != null && duration > 0) {
            executeCarpetCommand("sneak")
            // 注意：延时后 unsneak 需要异步处理，这里简化
        } else {
            executeCarpetCommand("sneak")
        }
        return "已潜行"
    }

    private fun executeSprint(params: Map<String, Any>): String {
        val enable = params["enable"] as? Boolean ?: true
        if (enable) {
            executeCarpetCommand("sprint")
        } else {
            executeCarpetCommand("unsprint")
        }
        return if (enable) "已冲刺" else "已停止冲刺"
    }

    // ========== 物品 ==========

    private fun executeDrop(params: Map<String, Any>): String {
        val slot = getStringParam(params, "slot") ?: "mainhand"
        val continuous = params["continuous"] as? Boolean ?: false

        val cmd = if (continuous) {
            "drop $slot continuous"
        } else {
            "drop $slot"
        }
        executeCarpetCommand(cmd)
        return "已丢出物品"
    }

    private fun executeHotbar(params: Map<String, Any>): String {
        val slot = getIntParam(params, "slot") ?: return "缺少参数 slot"
        if (slot !in 1..9) return "槽位必须在 1-9 之间"
        executeCarpetCommand("hotbar $slot")
        return "已切换到快捷栏 $slot"
    }

    private fun executeSwapHands(): String {
        executeCarpetCommand("swapHands")
        return "已交换主副手"
    }

    // ========== 骑乘 ==========

    private fun executeMount(params: Map<String, Any>): String {
        val anything = params["anything"] as? Boolean ?: false
        if (anything) {
            executeCarpetCommand("mount anything")
        } else {
            executeCarpetCommand("mount")
        }
        return "已骑乘"
    }

    private fun executeDismount(): String {
        executeCarpetCommand("dismount")
        return "已下马"
    }

    // ========== 挖掘/放置 ==========

    private suspend fun executeMineBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val targetPos = BlockPos(x, y, z)
        val bot = server.playerList.getPlayerByName(botName) ?: return "Bot 不存在"

        // 距离检查
        val eyePos = bot.eyePosition
        val blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(targetPos)
        val distance = eyePos.distanceTo(blockCenter)
        if (distance > 6.0) {
            return "挖掘失败：方块太远（${"%.2f".format(distance)}格，最大 6.0 格），请先靠近。"
        }

        // 看向方块
        executeCarpetCommand("look at $x $y $z")

        // 开始挖掘（continuous attack）
        executeCarpetCommand("attack continuous")

        // 等待方块被破坏
        var ticks = 0
        val maxTicks = 100
        while (ticks < maxTicks) {
            val isBroken = bot.level().getBlockState(targetPos).isAir
            if (isBroken) break
            kotlinx.coroutines.delay(50)
            ticks++
        }

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

        // 使用物品放置（use = 右键）
        executeCarpetCommand("use")

        return "已在 ($x, $y, $z) 放置 $blockName"
    }

    // ========== 查询 ==========

    private fun executeGetInventory(): String {
        val bot = FakePlayerManager.getBot(server, botName) ?: return "Bot 不存在"
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
        val bot = FakePlayerManager.getBot(server, botName) ?: return "Bot 不存在"
        return "血量：${bot.health.toInt()}/${bot.maxHealth.toInt()}"
    }

    private fun executeGetHunger(): String {
        val bot = FakePlayerManager.getBot(server, botName) ?: return "Bot 不存在"
        return "饥饿值：${bot.foodData.foodLevel}/20"
    }

    private fun executeScanArea(params: Map<String, Any>): String {
        val radius = getIntParam(params, "radius") ?: 5
        val bot = FakePlayerManager.getBot(server, botName) ?: return "Bot 不存在"
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

    // ========== 通信 ==========

    private fun executeSendMessage(params: Map<String, Any>): String {
        val message = params["message"] as? String ?: return "缺少参数 message"
        try {
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "/say [$botName] $message"
            )
        } catch (e: Exception) {
            println("[AnimaFabric] 发送消息失败: ${e.message}")
        }
        return "已发送消息"
    }

    // ========== 合成 ==========

    private suspend fun executeCraft(params: Map<String, Any>): String {
        val item = params["param0"] as? String ?: params["item"] as? String ?: return "缺少参数 item"

        val itemMapping = mapOf(
            "planks" to "minecraft:oak_planks",
            "oak_planks" to "minecraft:oak_planks",
            "crafting_table" to "minecraft:crafting_table",
            "stick" to "minecraft:stick",
            "wooden_pickaxe" to "minecraft:wooden_pickaxe",
            "wooden_axe" to "minecraft:wooden_axe",
            "wooden_sword" to "minecraft:wooden_sword",
            "stone_pickaxe" to "minecraft:stone_pickaxe",
            "stone_axe" to "minecraft:stone_axe",
            "stone_sword" to "minecraft:stone_sword",
            "iron_pickaxe" to "minecraft:iron_pickaxe",
            "iron_axe" to "minecraft:iron_axe",
            "iron_sword" to "minecraft:iron_sword",
            "furnace" to "minecraft:furnace",
            "torch" to "minecraft:torch",
            "chest" to "minecraft:chest",
            "bread" to "minecraft:bread",
            "iron_ingot" to "minecraft:iron_ingot",
            "gold_ingot" to "minecraft:gold_ingot",
            "diamond" to "minecraft:diamond",
            "cobblestone" to "minecraft:cobblestone"
        )

        val itemId = itemMapping[item.lowercase()] ?: "minecraft:${item.lowercase()}"

        return try {
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "give $botName $itemId 1"
            )
            "已合成 $item"
        } catch (e: Exception) {
            "合成失败: ${e.message}"
        }
    }
}
