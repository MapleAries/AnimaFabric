package com.maple.agent

import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
import com.maple.locate.StructureLocator
import com.maple.pathfinding.AStarPathfinder
import com.maple.pathfinding.MovementType
import com.maple.pathfinding.ToolCostCalculator
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * 工具执行器 - 通过 ActionDriver 控制 bot。
 */
class ActionExecutor(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    config: AnimaFabricConfig
) {
    private val driver = ActionDriverFactory.create(
        config.actionDriver,
        botName,
        server,
        config.allowAdminTools
    )

    private data class CraftIngredient(val options: List<String>, val count: Int)
    private data class CraftRecipe(
        val result: String,
        val resultCount: Int,
        val ingredients: List<CraftIngredient>,
        val requiresCraftingTable: Boolean = false
    )
    private data class MainHandSnapshot(val itemId: String?, val count: Int)
    private data class UseOnBlockSnapshot(
        val blockId: String,
        val mainHand: MainHandSnapshot,
        val distance: Double
    )
    private data class MiningSnapshot(
        val blockId: String?,
        val hardness: Float,
        val distance: Double,
        val canHarvest: Boolean,
        val miningTicks: Double,
        val inventory: Map<String, Int>
    )

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
            "equipItem" -> executeEquipItem(params)
            "useItem" -> executeUseItem(params)
            "useItemOnBlock" -> executeUseItemOnBlock(params)
            "eatFood" -> executeEatFood(params)
            "mineBlock" -> executeMineBlock(params)
            "placeBlock" -> executePlaceBlock(params)
            "findNearbyBlock" -> executeFindNearbyBlock(params)
            "findPortalFrame" -> executeFindPortalFrame(params)
            "buildNetherPortal" -> executeBuildNetherPortal(params)
            "ignitePortal" -> executeIgnitePortal(params)
            "enterPortal" -> executeEnterPortal(params)
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
     * 通过当前 ActionDriver 执行玩家动作。
     */
    private suspend fun executeCarpetCommand(command: String): Boolean {
        return driver.playerCommand(command)
    }

    private suspend fun executeServerCommand(command: String): Boolean {
        return driver.adminCommand(command)
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
            "cooked_beef" to "minecraft:cooked_beef",
            "cooked_porkchop" to "minecraft:cooked_porkchop",
            "flint_and_steel" to "minecraft:flint_and_steel",
            "obsidian" to "minecraft:obsidian",
            "ender_eye" to "minecraft:ender_eye",
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

    private suspend fun executeEquipItem(params: Map<String, Any>): String {
        val item = getStringParam(params, "item") ?: return "Missing parameter: item"
        val itemId = resolveItemId(item)
        return if (ensureMainHandItem(itemId)) {
            "Equipped $itemId in main hand"
        } else {
            "Failed to equip $itemId"
        }
    }

    private suspend fun executeUseItem(params: Map<String, Any>): String {
        val item = getStringParam(params, "item")
        if (item != null && !ensureMainHandItem(resolveItemId(item))) {
            return "Failed to equip ${resolveItemId(item)}"
        }
        executeCarpetCommand("use")
        return if (item != null) "Used $item" else "Used main hand item"
    }

    private suspend fun executeUseItemOnBlock(params: Map<String, Any>): String {
        val item = getStringParam(params, "item") ?: return "Missing parameter: item"
        val x = getIntParam(params, "x") ?: return "Missing parameter: x"
        val y = getIntParam(params, "y") ?: return "Missing parameter: y"
        val z = getIntParam(params, "z") ?: return "Missing parameter: z"
        val itemId = resolveItemId(item)

        if (!ensureMainHandItem(itemId)) {
            return "Failed to equip $itemId"
        }

        val targetPos = BlockPos(x, y, z)
        val before = captureUseOnBlockState(targetPos) ?: return "Bot does not exist"
        if (before.distance > 5.0) {
            return "Use failed: target block is too far (${"%.1f".format(before.distance)} blocks)"
        }

        executeCarpetCommand("look at $x $y $z")
        executeCarpetCommand("use")
        return if (waitForUseOnBlockChange(targetPos, before)) {
            "Used $itemId on block ($x, $y, $z)"
        } else {
            "Use failed: no block or item change detected at ($x, $y, $z)"
        }
    }

    private suspend fun executeEatFood(params: Map<String, Any>): String {
        val food = getStringParam(params, "item") ?: "bread"
        val itemId = resolveItemId(food)
        if (!ensureMainHandItem(itemId)) {
            return "Failed to equip food item $itemId"
        }

        val before = GameThreadDispatcher.runOnGameThread(server) {
            server.playerList.getPlayerByName(botName)?.foodData?.foodLevel
        } ?: return "Bot does not exist"

        executeCarpetCommand("use continuous")
        kotlinx.coroutines.delay(1800)
        executeCarpetCommand("stop")

        val after = GameThreadDispatcher.runOnGameThread(server) {
            server.playerList.getPlayerByName(botName)?.foodData?.foodLevel
        } ?: return "Bot does not exist"

        return "Ate $itemId (hunger $before -> $after)"
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
        val before = captureMiningState(targetPos) ?: return "Bot does not exist"
        if (before.blockId == null) return "Mine failed: target block is already air ($x, $y, $z)"
        if (before.hardness < 0.0f) return "Mine failed: ${before.blockId} is not breakable"
        if (before.distance > 5.0) {
            return "Mine failed: target block is too far (${"%.1f".format(before.distance)} blocks)"
        }
        if (!before.canHarvest) {
            return "Mine failed: current tool cannot harvest ${before.blockId}"
        }

        executeCarpetCommand("look at $x $y $z")
        val miningDelayMs = (before.miningTicks * 50.0).toLong().coerceIn(50L, 30000L)
        kotlinx.coroutines.delay(miningDelayMs)

        val destroyed = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
            bot.gameMode.destroyBlock(targetPos)
        }
        if (!destroyed) return "Mine failed: game mode refused to destroy ${before.blockId}"

        val broken = waitForBlockAir(targetPos)
        if (!broken) return "Mine failed: ${before.blockId} did not break at ($x, $y, $z)"

        val afterInventory = inventoryCounts() ?: return "Mined ${before.blockId}, but inventory check failed"
        val gained = inventoryGainSummary(before.inventory, afterInventory)
        return if (gained.isBlank()) {
            "Mined ${before.blockId} at ($x, $y, $z); no inventory pickup detected"
        } else {
            "Mined ${before.blockId} at ($x, $y, $z); picked up $gained"
        }
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

        val placementDistance = distanceToBlockCenter(supportPos) ?: return "Bot 不存在"
        if (placementDistance > 5.0) {
            return "Place failed: support block is too far (${"%.1f".format(placementDistance)} blocks)"
        }

        val beforeHand = mainHandSnapshot() ?: return "Bot 不存在"

        // 看向支撑方块，让右键能把方块放到目标空气格。
        executeCarpetCommand("look at ${supportPos.x} ${supportPos.y} ${supportPos.z}")

        // 使用物品放置（use = 右键）
        executeCarpetCommand("use")

        val placedBlock = waitForPlacedBlock(targetPos, itemId)
        val afterHand = mainHandSnapshot()
        return if (placedBlock != null) {
            "已在 (${targetPos.x}, ${targetPos.y}, ${targetPos.z}) 放置 $placedBlock"
        } else if (afterHand != null && afterHand != beforeHand) {
            "Place failed: item changed but target block did not update at (${targetPos.x}, ${targetPos.y}, ${targetPos.z})"
        } else {
            "放置失败：目标位置 (${targetPos.x}, ${targetPos.y}, ${targetPos.z}) 未变为 $blockName，请检查距离、准星面和主手方块"
        }
    }

    private suspend fun executeFindNearbyBlock(params: Map<String, Any>): String {
        val block = getStringParam(params, "block") ?: return "Missing parameter: block"
        val radius = (getIntParam(params, "radius") ?: 8).coerceIn(1, 64)
        val normalized = normalizeBlockId(block)

        val matches = findNearbyBlocks(normalized, radius, limit = 10)
        if (matches.isEmpty()) return "No $normalized found within radius $radius"

        return matches.joinToString("\n") {
            "$normalized at (${it.x}, ${it.y}, ${it.z})"
        }
    }

    private suspend fun executeFindPortalFrame(params: Map<String, Any>): String {
        val radius = (getIntParam(params, "radius") ?: 16).coerceIn(1, 64)
        val frames = findNearbyBlocks("minecraft:end_portal_frame", radius, limit = 20)
        if (frames.isEmpty()) return "No end portal frame found within radius $radius"

        return frames.joinToString("\n") {
            "end_portal_frame at (${it.x}, ${it.y}, ${it.z})"
        }
    }

    private suspend fun executeBuildNetherPortal(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "Missing parameter: x"
        val y = getIntParam(params, "y") ?: return "Missing parameter: y"
        val z = getIntParam(params, "z") ?: return "Missing parameter: z"
        val axis = getStringParam(params, "axis")?.lowercase() ?: "x"
        if (axis !in setOf("x", "z")) return "Invalid axis: $axis"

        val origin = BlockPos(x, y, z)
        for (pos in netherPortalFramePositions(origin, axis)) {
            if (!executeServerCommand("setblock ${pos.x} ${pos.y} ${pos.z} minecraft:obsidian")) {
                return "Failed to place obsidian at (${pos.x}, ${pos.y}, ${pos.z})"
            }
        }
        for (pos in netherPortalInnerPositions(origin, axis)) {
            executeServerCommand("setblock ${pos.x} ${pos.y} ${pos.z} minecraft:air")
        }

        return "Built nether portal frame at ($x, $y, $z) axis=$axis"
    }

    private suspend fun executeIgnitePortal(params: Map<String, Any>): String {
        val x = getIntParam(params, "x") ?: return "Missing parameter: x"
        val y = getIntParam(params, "y") ?: return "Missing parameter: y"
        val z = getIntParam(params, "z") ?: return "Missing parameter: z"
        val axis = getStringParam(params, "axis")?.lowercase() ?: "x"
        if (axis !in setOf("x", "z")) return "Invalid axis: $axis"

        val origin = BlockPos(x, y, z)
        val innerBottom = if (axis == "x") origin.offset(1, 1, 0) else origin.offset(0, 1, 1)

        ensureMainHandItem("minecraft:flint_and_steel")
        executeCarpetCommand("look at ${innerBottom.x} ${innerBottom.y} ${innerBottom.z}")
        executeCarpetCommand("use")
        kotlinx.coroutines.delay(500)

        val activated = GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
            netherPortalInnerPositions(origin, axis).any {
                blockId(bot.level().getBlockState(it).block) == "minecraft:nether_portal"
            }
        }
        if (activated) return "Ignited nether portal at ($x, $y, $z)"

        executeServerCommand("setblock ${innerBottom.x} ${innerBottom.y} ${innerBottom.z} minecraft:fire")
        return "Attempted to ignite nether portal at ($x, $y, $z)"
    }

    private suspend fun executeEnterPortal(params: Map<String, Any>): String {
        val radius = (getIntParam(params, "radius") ?: 8).coerceIn(1, 32)
        val portalPos = findNearestBlock("minecraft:nether_portal", radius)
            ?: return "No nether portal found within radius $radius"

        val moveResult = executeMoveTo(mapOf("x" to portalPos.x, "y" to portalPos.y, "z" to portalPos.z))
        kotlinx.coroutines.delay(4000)
        return "Entered or moved into nearest nether portal at (${portalPos.x}, ${portalPos.y}, ${portalPos.z}): $moveResult"
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
        return equipExistingInventoryItem(itemId)
    }

    private suspend fun isMainHandItem(itemId: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
            val stack = bot.mainHandItem
            !stack.isEmpty && BuiltInRegistries.ITEM.getKey(stack.item).toString().equals(itemId, ignoreCase = true)
        }
    }

    private suspend fun equipExistingInventoryItem(itemId: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
            val inventory = bot.inventory
            val slot = (0 until inventory.containerSize).firstOrNull { index ->
                val stack = inventory.getItem(index)
                !stack.isEmpty && BuiltInRegistries.ITEM.getKey(stack.item).toString().equals(itemId, ignoreCase = true)
            } ?: return@runOnGameThread false

            if (slot in 0..8) {
                inventory.setSelectedSlot(slot)
                return@runOnGameThread true
            }

            val selectedSlot = inventory.getSelectedSlot()
            val selectedStack = inventory.getItem(selectedSlot)
            val targetStack = inventory.getItem(slot)
            inventory.setItem(selectedSlot, targetStack)
            inventory.setItem(slot, selectedStack)
            true
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

    private fun normalizeBlockId(block: String): String {
        val normalized = block.lowercase()
            .replace(' ', '_')
            .removePrefix("minecraft:")
        return "minecraft:$normalized"
    }

    private fun blockId(block: net.minecraft.world.level.block.Block): String {
        return BuiltInRegistries.BLOCK.getKey(block).toString()
    }

    private fun blockStateId(state: net.minecraft.world.level.block.state.BlockState): String {
        return blockId(state.block)
    }

    private fun itemId(item: Item): String {
        return BuiltInRegistries.ITEM.getKey(item).toString()
    }

    private suspend fun mainHandSnapshot(): MainHandSnapshot? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            val stack = bot.mainHandItem
            if (stack.isEmpty) {
                MainHandSnapshot(null, 0)
            } else {
                MainHandSnapshot(itemId(stack.item), stack.count)
            }
        }
    }

    private suspend fun captureUseOnBlockState(targetPos: BlockPos): UseOnBlockSnapshot? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            val stack = bot.mainHandItem
            val hand = if (stack.isEmpty) {
                MainHandSnapshot(null, 0)
            } else {
                MainHandSnapshot(itemId(stack.item), stack.count)
            }
            UseOnBlockSnapshot(
                blockStateId(bot.level().getBlockState(targetPos)),
                hand,
                bot.eyePosition.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(targetPos))
            )
        }
    }

    private suspend fun waitForUseOnBlockChange(targetPos: BlockPos, before: UseOnBlockSnapshot): Boolean {
        repeat(10) {
            val changed = GameThreadDispatcher.runOnGameThread(server) {
                val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
                val currentBlock = blockStateId(bot.level().getBlockState(targetPos))
                val stack = bot.mainHandItem
                val currentHand = if (stack.isEmpty) {
                    MainHandSnapshot(null, 0)
                } else {
                    MainHandSnapshot(itemId(stack.item), stack.count)
                }
                currentBlock != before.blockId || currentHand != before.mainHand
            }
            if (changed) return true
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    private suspend fun captureMiningState(targetPos: BlockPos): MiningSnapshot? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            val level = bot.level()
            val state = level.getBlockState(targetPos)
            val blockId = if (state.isAir) null else blockStateId(state)
            MiningSnapshot(
                blockId,
                if (state.isAir) 0.0f else state.getDestroySpeed(level, targetPos),
                bot.eyePosition.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(targetPos)),
                state.isAir || ToolCostCalculator.canHarvest(bot, state),
                ToolCostCalculator.getMiningCost(level, targetPos, bot),
                inventoryCountsInGameThread(bot)
            )
        }
    }

    private suspend fun waitForBlockAir(targetPos: BlockPos): Boolean {
        repeat(20) {
            val isAir = GameThreadDispatcher.runOnGameThread(server) {
                val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false
                bot.level().getBlockState(targetPos).isAir
            }
            if (isAir) return true
            kotlinx.coroutines.delay(100)
        }
        return false
    }

    private suspend fun inventoryCounts(): Map<String, Int>? {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread null
            inventoryCountsInGameThread(bot)
        }
    }

    private fun inventoryCountsInGameThread(bot: net.minecraft.server.level.ServerPlayer): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (slot in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val id = itemId(stack.item)
                counts[id] = (counts[id] ?: 0) + stack.count
            }
        }
        return counts
    }

    private fun inventoryGainSummary(before: Map<String, Int>, after: Map<String, Int>): String {
        return after.entries
            .mapNotNull { (item, count) ->
                val delta = count - (before[item] ?: 0)
                if (delta > 0) "$item x$delta" else null
            }
            .joinToString(", ")
    }

    private fun itemById(itemId: String): Item? {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(null)
    }

    private fun countInventoryItems(bot: net.minecraft.server.level.ServerPlayer, options: List<String>): Int {
        var count = 0
        for (slot in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(slot)
            if (!stack.isEmpty && options.any { itemId(stack.item).equals(it, ignoreCase = true) }) {
                count += stack.count
            }
        }
        return count
    }

    private fun removeInventoryItems(
        bot: net.minecraft.server.level.ServerPlayer,
        options: List<String>,
        count: Int
    ): Boolean {
        if (countInventoryItems(bot, options) < count) return false

        var remaining = count
        for (slot in 0 until bot.inventory.containerSize) {
            if (remaining <= 0) break
            val stack = bot.inventory.getItem(slot)
            if (stack.isEmpty || options.none { itemId(stack.item).equals(it, ignoreCase = true) }) continue

            val removed = minOf(remaining, stack.count)
            stack.shrink(removed)
            if (stack.isEmpty) {
                bot.inventory.setItem(slot, ItemStack.EMPTY)
            }
            remaining -= removed
        }
        return remaining == 0
    }

    private fun addInventoryItem(bot: net.minecraft.server.level.ServerPlayer, itemId: String, count: Int): Boolean {
        val item = itemById(itemId) ?: return false
        return bot.inventory.add(ItemStack(item, count))
    }

    private fun canFitCraftResult(bot: net.minecraft.server.level.ServerPlayer, recipe: CraftRecipe): Boolean {
        val resultItem = itemById(recipe.result) ?: return false
        val maxStackSize = resultItem.defaultMaxStackSize
        val simulatedCounts = mutableMapOf<Int, Int>()

        for (slot in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(slot)
            if (stack.isEmpty) return true
            simulatedCounts[slot] = stack.count
        }

        for (ingredient in recipe.ingredients) {
            var remaining = ingredient.count
            for (slot in 0 until bot.inventory.containerSize) {
                if (remaining <= 0) break
                val stack = bot.inventory.getItem(slot)
                if (stack.isEmpty || ingredient.options.none { itemId(stack.item).equals(it, ignoreCase = true) }) {
                    continue
                }
                val current = simulatedCounts[slot] ?: stack.count
                val removed = minOf(current, remaining)
                simulatedCounts[slot] = current - removed
                remaining -= removed
            }
            if (remaining > 0) return false
        }

        if (simulatedCounts.values.any { it <= 0 }) return true

        for (slot in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(slot)
            if (!stack.isEmpty && itemId(stack.item).equals(recipe.result, ignoreCase = true)) {
                val simulatedCount = simulatedCounts[slot] ?: stack.count
                if (simulatedCount + recipe.resultCount <= maxStackSize) return true
            }
        }

        return false
    }

    private fun supportedCraftRecipes(): Map<String, CraftRecipe> {
        val logs = listOf(
            "minecraft:oak_log",
            "minecraft:spruce_log",
            "minecraft:birch_log",
            "minecraft:jungle_log",
            "minecraft:acacia_log",
            "minecraft:dark_oak_log",
            "minecraft:mangrove_log",
            "minecraft:cherry_log",
            "minecraft:pale_oak_log",
            "minecraft:crimson_stem",
            "minecraft:warped_stem"
        )
        val planks = listOf(
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks",
            "minecraft:cherry_planks",
            "minecraft:pale_oak_planks",
            "minecraft:crimson_planks",
            "minecraft:warped_planks"
        )
        val stones = listOf("minecraft:cobblestone", "minecraft:cobbled_deepslate")

        fun recipe(
            result: String,
            resultCount: Int,
            ingredients: List<CraftIngredient>,
            table: Boolean = false
        ) = CraftRecipe(result, resultCount, ingredients, table)

        fun ingredient(count: Int, vararg options: String) = CraftIngredient(options.toList(), count)
        fun planksIngredient(count: Int) = CraftIngredient(planks, count)
        fun logsIngredient(count: Int) = CraftIngredient(logs, count)
        fun stonesIngredient(count: Int) = CraftIngredient(stones, count)

        return mapOf(
            "minecraft:oak_planks" to recipe("minecraft:oak_planks", 4, listOf(logsIngredient(1))),
            "minecraft:stick" to recipe("minecraft:stick", 4, listOf(planksIngredient(2))),
            "minecraft:crafting_table" to recipe("minecraft:crafting_table", 1, listOf(planksIngredient(4))),
            "minecraft:wooden_pickaxe" to recipe("minecraft:wooden_pickaxe", 1, listOf(planksIngredient(3), ingredient(2, "minecraft:stick")), table = true),
            "minecraft:wooden_axe" to recipe("minecraft:wooden_axe", 1, listOf(planksIngredient(3), ingredient(2, "minecraft:stick")), table = true),
            "minecraft:wooden_sword" to recipe("minecraft:wooden_sword", 1, listOf(planksIngredient(2), ingredient(1, "minecraft:stick"))),
            "minecraft:stone_pickaxe" to recipe("minecraft:stone_pickaxe", 1, listOf(stonesIngredient(3), ingredient(2, "minecraft:stick")), table = true),
            "minecraft:stone_axe" to recipe("minecraft:stone_axe", 1, listOf(stonesIngredient(3), ingredient(2, "minecraft:stick")), table = true),
            "minecraft:stone_sword" to recipe("minecraft:stone_sword", 1, listOf(stonesIngredient(2), ingredient(1, "minecraft:stick"))),
            "minecraft:furnace" to recipe("minecraft:furnace", 1, listOf(stonesIngredient(8)), table = true),
            "minecraft:torch" to recipe("minecraft:torch", 4, listOf(ingredient(1, "minecraft:coal", "minecraft:charcoal"), ingredient(1, "minecraft:stick")))
        )
    }

    private suspend fun findNearestBlock(block: String, radius: Int): BlockPos? {
        return findNearbyBlocks(block, radius, limit = 1).firstOrNull()
    }

    private suspend fun findNearbyBlocks(block: String, radius: Int, limit: Int): List<BlockPos> {
        val targetId = normalizeBlockId(block)
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread emptyList()
            val level = bot.level()
            val center = bot.blockPosition()
            val matches = mutableListOf<BlockPos>()

            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    for (dz in -radius..radius) {
                        val pos = center.offset(dx, dy, dz)
                        if (blockId(level.getBlockState(pos).block) == targetId) {
                            matches.add(pos)
                        }
                    }
                }
            }

            matches.sortedBy { it.distSqr(center) }.take(limit)
        }
    }

    private fun netherPortalFramePositions(origin: BlockPos, axis: String): List<BlockPos> {
        val positions = mutableListOf<BlockPos>()
        for (width in 0..3) {
            for (height in 0..4) {
                val isFrame = width == 0 || width == 3 || height == 0 || height == 4
                if (isFrame) {
                    positions.add(if (axis == "x") origin.offset(width, height, 0) else origin.offset(0, height, width))
                }
            }
        }
        return positions
    }

    private fun netherPortalInnerPositions(origin: BlockPos, axis: String): List<BlockPos> {
        val positions = mutableListOf<BlockPos>()
        for (width in 1..2) {
            for (height in 1..3) {
                positions.add(if (axis == "x") origin.offset(width, height, 0) else origin.offset(0, height, width))
            }
        }
        return positions
    }

    private suspend fun waitForPlacedBlock(targetPos: BlockPos, expectedBlockId: String? = null): String? {
        repeat(15) {
            val (botExists, currentBlock) = GameThreadDispatcher.runOnGameThread(server) {
                val bot = server.playerList.getPlayerByName(botName) ?: return@runOnGameThread false to null
                val state = bot.level().getBlockState(targetPos)
                val currentId = if (!state.isAir) blockStateId(state) else null
                true to currentId
            }

            if (!botExists) return null
            if (currentBlock != null && (expectedBlockId == null || currentBlock.equals(expectedBlockId, ignoreCase = true))) {
                return currentBlock
            }
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
        return if (driver.sendMessage(message)) {
            "已发送消息"
        } else {
            "发送消息失败"
        }
    }

    // ========== 合成 ==========

    private suspend fun executeCraft(params: Map<String, Any>): String {
        val item = params["param0"] as? String ?: params["item"] as? String ?: return "缺少参数 item"
        val itemId = resolveItemId(item)
        val recipes = supportedCraftRecipes()
        val recipe = recipes[itemId]

        if (recipe == null) {
            return if (executeServerCommand("give $botName $itemId 1")) {
                "已在创造/OP 模式给予 $item"
            } else {
                "暂不支持合成 $itemId，且当前假人无权使用 admin 兜底"
            }
        }

        val crafted = GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread null

            if (recipe.requiresCraftingTable && countInventoryItems(bot, listOf("minecraft:crafting_table")) <= 0) {
                return@runOnGameThread "缺少 crafting_table"
            }

            val missing = recipe.ingredients.firstOrNull { ingredient ->
                countInventoryItems(bot, ingredient.options) < ingredient.count
            }
            if (missing != null) {
                val names = missing.options.joinToString("/")
                return@runOnGameThread "缺少材料 $names x${missing.count}"
            }

            if (!canFitCraftResult(bot, recipe)) {
                return@runOnGameThread "背包已满，无法放入 ${recipe.result}"
            }

            for (ingredient in recipe.ingredients) {
                if (!removeInventoryItems(bot, ingredient.options, ingredient.count)) {
                    return@runOnGameThread "消耗材料失败"
                }
            }

            if (!addInventoryItem(bot, recipe.result, recipe.resultCount)) {
                return@runOnGameThread "背包已满，无法放入 ${recipe.result}"
            }

            "已合成 ${recipe.result} x${recipe.resultCount}"
        }

        if (crafted != null && crafted.startsWith("已合成")) {
            return crafted
        }

        return if (executeServerCommand("give $botName $itemId 1")) {
            "已在创造/OP 模式给予 $item"
        } else {
            crafted ?: "Bot 不存在"
        }
    }
}
