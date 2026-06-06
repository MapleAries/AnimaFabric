package com.maple.agent

import com.maple.entity.FakePlayerManager
import com.maple.locate.StructureLocator
import com.maple.pathfinding.AStarPathfinder
import com.maple.pathfinding.MovementType
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries

/**
 * 工具执行器 - 通过 Carpet /player 命令控制 bot。
 * 完整支持 Carpet 的所有 player 子命令。
 */
class ActionExecutor(private val botName: String, private val server: net.minecraft.server.MinecraftServer) {

    /**
     * 执行单个工具调用，返回执行结果。
     */
    suspend fun execute(toolName: String, params: Map<String, Any>): String {
        // 将位置参数映射为命名参数
        val resolvedParams = resolvePositionalParams(toolName, params)
        return executeInternal(toolName, resolvedParams)
    }

    private fun resolvePositionalParams(toolName: String, params: Map<String, Any>): Map<String, Any> {
        if (params.keys.none { it.startsWith("param") }) return params

        val tool = com.maple.agent.ToolRegistry.getTool(toolName) ?: return params
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
            "jump" -> executeJump(params)
            "attack" -> executeAttack(params)
            "use" -> executeUse(params)
            "mineBlock" -> executeMineBlock(params)
            "placeBlock" -> executePlaceBlock(params)
            "getInventory" -> executeGetInventory()
            "getHealth" -> executeGetHealth()
            "getHunger" -> executeGetHunger()
            "scanArea" -> executeScanArea(params)
            "locateStructure" -> executeLocateStructure(params)
            "sendMessage" -> executeSendMessage(params)
            "msg" -> executeSendMessage(params)
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
    private suspend fun executeCarpetCommand(command: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                val fullCommand = "/player $botName $command"
                println("[AnimaFabric] 执行 carpet 命令: $fullCommand")
                server.commands.performPrefixedCommand(server.createCommandSourceStack(), fullCommand)
                true
            } catch (e: Exception) {
                println("[AnimaFabric] carpet 命令执行异常: ${e.message}")
                false
            }
        }
    }

    private suspend fun executeServerCommand(command: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                println("[AnimaFabric] 执行服务器命令: $command")
                server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
                true
            } catch (e: Exception) {
                println("[AnimaFabric] 服务器命令执行异常: ${e.message}")
                false
            }
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

    private fun resolveItemId(item: String): String {
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
        val normalized = item.lowercase().removePrefix("minecraft:")
        return itemMapping[normalized] ?: "minecraft:$normalized"
    }

    // ========== 移动 ==========

    private suspend fun executeMoveTo(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val (startPos, pathSteps) = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            val level = bot.level()
            val foundPath = AStarPathfinder.findPathSteps(level, bot.blockPosition(), BlockPos(x, y, z))
            bot.position() to foundPath
        } ?: return "Bot 不存在"

        if (pathSteps.isEmpty()) {
            // fallback 到直线移动
            val dx = x - startPos.x
            val dz = z - startPos.z
            val distance = Math.sqrt(dx * dx + dz * dz)

            executeCarpetCommand("look at $x $y $z")
            executeCarpetCommand("move forward")
            kotlinx.coroutines.delay((distance * 250L).toLong().coerceIn(500, 10000))
            executeCarpetCommand("stop")

            val endPos = GameThreadDispatcher.runOnGameThread(server) {
                server.playerList.getPlayerByName(botName)?.position()
            } ?: return "Bot 不存在"
            val movedDistance = startPos.distanceTo(endPos)
            return "已移动到 ($x, $y, $z) 附近（移动了${"%.1f".format(movedDistance)}格，无路径）"
        }

        // 沿 A* 路径移动
        for (step in pathSteps) {
            val stepResult = executePathStep(step)
            if (stepResult != null) return stepResult
        }

        val endPos = GameThreadDispatcher.runOnGameThread(server) {
            server.playerList.getPlayerByName(botName)?.position()
        } ?: return "Bot 不存在"
        val movedDistance = startPos.distanceTo(endPos)
        val remainingDistance = endPos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(BlockPos(x, y, z)))
        if (remainingDistance > 2.0) {
            return "移动未完成：目标 ($x, $y, $z)，当前距离 ${"%.1f".format(remainingDistance)} 格"
        }
        return "已移动到 ($x, $y, $z) 附近（移动了${"%.1f".format(movedDistance)}格，剩余 ${"%.1f".format(remainingDistance)}格）"
    }

    private suspend fun executePathStep(step: AStarPathfinder.PathStep): String? {
        val target = step.to
        executeCarpetCommand("look at ${target.x} ${target.y} ${target.z}")

        try {
            when (step.moveType) {
                in MovementType.ASCEND -> {
                    executeCarpetCommand("jump")
                    kotlinx.coroutines.delay(100)
                    executeCarpetCommand("move forward")
                }
                in MovementType.HORIZONTAL,
                in MovementType.DIAGONAL,
                in MovementType.DESCEND -> {
                    executeCarpetCommand("move forward")
                }
                else -> {
                    return "移动未完成：路径包含暂不支持的移动类型 ${step.moveType}"
                }
            }

            val maxDurationMs = (step.cost * 80L).toLong().coerceIn(700L, 4000L)
            val reached = waitForStepTarget(target, maxDurationMs)
            if (!reached) {
                val distance = distanceToBlockCenter(target) ?: return "Bot 不存在"
                return "移动未完成：未能到达路径点 (${target.x}, ${target.y}, ${target.z})，当前距离 ${"%.1f".format(distance)} 格"
            }
            return null
        } finally {
            executeCarpetCommand("stop")
        }
    }

    private suspend fun waitForStepTarget(target: BlockPos, maxDurationMs: Long): Boolean {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < maxDurationMs) {
            val distance = distanceToBlockCenter(target) ?: return false
            if (distance <= 0.9) return true
            kotlinx.coroutines.delay(100)
        }
        return (distanceToBlockCenter(target) ?: return false) <= 1.2
    }

    private suspend fun distanceToBlockCenter(target: BlockPos): Double? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))
        }
    }

    private suspend fun executeMove(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"
        val distance = getIntParam(params, "ticks") ?: 5

        val startPos = GameThreadDispatcher.runOnGameThread(server) {
            server.playerList.getPlayerByName(botName)?.position()
        } ?: return "Bot 不存在"

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

        val endPos = GameThreadDispatcher.runOnGameThread(server) {
            server.playerList.getPlayerByName(botName)?.position()
        } ?: return "Bot 不存在"
        val movedDistance = startPos.distanceTo(endPos)
        return "已向 $direction 移动（约${"%.1f".format(movedDistance)}格）"
    }

    // ========== 视角 ==========

    private suspend fun executeLook(params: Map<String, Any>): String {
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

    private suspend fun executeTurn(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"

        // Carpet turn: back/left/right 或 <pitch> <yaw>
        when (direction.lowercase()) {
            "back", "left", "right" -> executeCarpetCommand("turn $direction")
            else -> return "无效方向：$direction（可用：back/left/right）"
        }

        return "已转向 $direction"
    }

    // ========== 动作 ==========

    private suspend fun executeJump(params: Map<String, Any>): String {
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

    private suspend fun executeStop(): String {
        executeCarpetCommand("stop")
        return "已停止所有动作"
    }

    // ========== 潜行/冲刺 ==========

    private suspend fun executeSneak(params: Map<String, Any>): String {
        val duration = getIntParam(params, "duration")
        if (duration != null && duration > 0) {
            executeCarpetCommand("sneak")
            // 注意：延时后 unsneak 需要异步处理，这里简化
        } else {
            executeCarpetCommand("sneak")
        }
        return "已潜行"
    }

    private suspend fun executeSprint(params: Map<String, Any>): String {
        val enable = params["enable"] as? Boolean ?: true
        if (enable) {
            executeCarpetCommand("sprint")
        } else {
            executeCarpetCommand("unsprint")
        }
        return if (enable) "已冲刺" else "已停止冲刺"
    }

    // ========== 物品 ==========

    private suspend fun executeDrop(params: Map<String, Any>): String {
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

    private suspend fun executeHotbar(params: Map<String, Any>): String {
        val slot = getIntParam(params, "slot") ?: return "缺少参数 slot"
        if (slot !in 1..9) return "槽位必须在 1-9 之间"
        executeCarpetCommand("hotbar $slot")
        return "已切换到快捷栏 $slot"
    }

    private suspend fun executeSwapHands(): String {
        executeCarpetCommand("swapHands")
        return "已交换主副手"
    }

    // ========== 骑乘 ==========

    private suspend fun executeMount(params: Map<String, Any>): String {
        val anything = params["anything"] as? Boolean ?: false
        if (anything) {
            executeCarpetCommand("mount anything")
        } else {
            executeCarpetCommand("mount")
        }
        return "已骑乘"
    }

    private suspend fun executeDismount(): String {
        executeCarpetCommand("dismount")
        return "已下马"
    }

    // ========== 挖掘/放置 ==========

    private suspend fun executeMineBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"

        val targetPos = BlockPos(x, y, z)
        val blockCenter = net.minecraft.world.phys.Vec3.atCenterOf(targetPos)

        // 距离检查：超过 3 格先靠近
        val distance = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            bot.eyePosition.distanceTo(blockCenter)
        } ?: return "Bot 不存在"

        if (distance > 3.0) {
            // 先移动到方块附近
            val botPos = GameThreadDispatcher.runOnGameThread(server) {
                server.playerList.getPlayerByName(botName)?.blockPosition()
            } ?: return "Bot 不存在"
            val dx = x - botPos.x
            val dz = z - botPos.z
            val dist = Math.sqrt((dx * dx + dz * dz).toDouble()).toInt()

            // 向方块方向移动（保持安全距离 2 格）
            val moveSteps = (dist - 2).coerceAtLeast(1)
            executeCarpetCommand("look at $x $y $z")
            executeCarpetCommand("move forward")
            kotlinx.coroutines.delay((moveSteps * 250L).coerceAtMost(3000L))
            executeCarpetCommand("stop")

            // 重新检查距离
            val newDist = GameThreadDispatcher.runOnGameThread(server) {
                val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
                bot.eyePosition.distanceTo(blockCenter)
            } ?: return "Bot 不存在"
            if (newDist > 5.0) {
                return "挖掘失败：无法靠近方块（当前距离 ${"%.1f".format(newDist)} 格）"
            }
        }

        // 看向方块
        executeCarpetCommand("look at $x $y $z")

        // 开始挖掘
        executeCarpetCommand("attack continuous")

        // 等待方块被破坏
        var ticks = 0
        val maxTicks = 100
        var isBroken = false
        while (ticks < maxTicks) {
            isBroken = GameThreadDispatcher.runOnGameThread(server) {
                server.playerList.getPlayerByName(botName)?.level()?.getBlockState(targetPos)?.isAir ?: false
            }
            if (isBroken) break
            kotlinx.coroutines.delay(50)
            ticks++
        }

        executeCarpetCommand("stop")
        if (!isBroken) {
            return "挖掘失败：方块在 ${maxTicks} ticks 内未被破坏 ($x, $y, $z)"
        }
        return "已挖掘 ($x, $y, $z)"
    }

    private suspend fun executePlaceBlock(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "缺少参数 x"
        val y = getIntParam(params, "y") ?: return "缺少参数 y"
        val z = getIntParam(params, "z") ?: return "缺少参数 z"
        val blockName = params["block"] as? String ?: return "缺少参数 block"
        val requestedPos = BlockPos(x, y, z)
        val itemId = resolveItemId(blockName)

        val (targetPos, supportPos) = resolvePlacementTarget(requestedPos)
            ?: return "放置失败：目标位置 ($x, $y, $z) 已被占用，且无法推断可放置的相邻空气格"

        if (!ensureMainHandItem(itemId)) {
            return "放置失败：无法将 $blockName 准备到主手"
        }

        // 看向支撑方块，让右键能把方块放到目标空气格。
        executeCarpetCommand("look at ${supportPos.x} ${supportPos.y} ${supportPos.z}")

        // 使用物品放置（use = 右键）
        executeCarpetCommand("use")

        val placedBlock = waitForPlacedBlock(targetPos)
        return if (placedBlock != null) {
            "已在 (${targetPos.x}, ${targetPos.y}, ${targetPos.z}) 放置 $placedBlock"
        } else {
            "放置失败：目标位置 (${targetPos.x}, ${targetPos.y}, ${targetPos.z}) 未变为 $blockName，请检查距离、准星面和主手方块"
        }
    }

    private suspend fun resolvePlacementTarget(requestedPos: BlockPos): Pair<BlockPos, BlockPos>? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            val level = bot.level()
            val requestedState = level.getBlockState(requestedPos)

            if (requestedState.isAir) {
                val support = findPlacementSupportInLevel(level, requestedPos)
                if (support != null) requestedPos to support else null
            } else {
                val above = requestedPos.above()
                if (level.getBlockState(above).isAir) above to requestedPos else null
            }
        }
    }

    private suspend fun ensureMainHandItem(itemId: String): Boolean {
        if (isMainHandItem(itemId)) return true
        if (!executeServerCommand("item replace entity $botName weapon.mainhand with $itemId 1")) {
            return false
        }
        kotlinx.coroutines.delay(100)
        return isMainHandItem(itemId)
    }

    private suspend fun isMainHandItem(itemId: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
            val stack = bot.mainHandItem
            !stack.isEmpty && BuiltInRegistries.ITEM.getKey(stack.item).toString().equals(itemId, ignoreCase = true)
        }
    }

    private fun findPlacementSupportInLevel(
        level: net.minecraft.world.level.Level,
        targetPos: BlockPos
    ): BlockPos? {
        return listOf(
            targetPos.below(),
            targetPos.north(),
            targetPos.south(),
            targetPos.east(),
            targetPos.west(),
            targetPos.above()
        ).firstOrNull { !level.getBlockState(it).isAir }
    }

    private suspend fun waitForPlacedBlock(targetPos: BlockPos): String? {
        repeat(15) {
            val (botExists, currentBlock) = GameThreadDispatcher.runOnGameThread(server) {
                val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false to null
                val state = bot.level().getBlockState(targetPos)
                true to if (!state.isAir) state.block.name.string else null
            }

            if (!botExists) return null
            if (currentBlock != null) return currentBlock
            kotlinx.coroutines.delay(100)
        }
        return null
    }

    // ========== 查询 ==========

    private suspend fun executeGetInventory(): String {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread "Bot 不存在"
            val items = mutableListOf<String>()
            for (i in 0 until bot.inventory.containerSize) {
                val stack = bot.inventory.getItem(i)
                if (!stack.isEmpty) {
                    items.add("${stack.hoverName.string} x${stack.count}")
                }
            }
            if (items.isEmpty()) "背包为空" else items.joinToString("\n")
        }
    }

    private suspend fun executeGetHealth(): String {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread "Bot 不存在"
            "血量：${bot.health.toInt()}/${bot.maxHealth.toInt()}"
        }
    }

    private suspend fun executeGetHunger(): String {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread "Bot 不存在"
            "饥饿值：${bot.foodData.foodLevel}/20"
        }
    }

    private suspend fun executeScanArea(params: Map<String, Any>): String {
        val radius = getIntParam(params, "radius") ?: 5
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread "Bot 不存在"
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

            if (blockCounts.isEmpty()) {
                "半径 ${radius} 格内没有方块"
            } else {
                blockCounts.entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .joinToString("\n") { "${it.key} x${it.value}" }
            }
        }
    }

    private suspend fun executeLocateStructure(params: Map<String, Any>): String {
        val structureName = getStringParam(params, "structure")
            ?: getStringParam(params, "name")
            ?: return "缺少参数 structure"
        val radius = (getIntParam(params, "radius") ?: 100).coerceIn(1, 500)
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread "Bot 不存在"
            StructureLocator.locate(bot.level(), bot.blockPosition(), structureName, radius)
        }
    }

    // ========== 通信 ==========

    private suspend fun executeSendMessage(params: Map<String, Any>): String {
        val message = getStringParam(params, "message") ?: getStringParam(params, "param0") ?: return "缺少参数 message"
        GameThreadDispatcher.runOnGameThread(server) {
            try {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "/say [$botName] $message"
                )
            } catch (e: Exception) {
                println("[AnimaFabric] 发送消息失败: ${e.message}")
            }
        }
        return "已发送消息"
    }

    // ========== 合成 ==========

    private suspend fun executeCraft(params: Map<String, Any>): String {
        val item = params["param0"] as? String ?: params["item"] as? String ?: return "缺少参数 item"
        val itemId = resolveItemId(item)

        return try {
            GameThreadDispatcher.runOnGameThread(server) {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "give $botName $itemId 1"
                )
            }
            "已给予 $item"
        } catch (e: Exception) {
            "给予物品失败: ${e.message}"
        }
    }
}
