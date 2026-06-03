package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * 世界感知系统。
 * 支持准星目标检测和多玩家上下文。
 */
object WorldPerception {

    private val IMPORTANT_BLOCKS = setOf(
        "Oak Log", "Birch Log", "Spruce Log", "Jungle Log", "Acacia Log", "Dark Oak Log",
        "Stone", "Cobblestone", "Coal Ore", "Iron Ore", "Gold Ore", "Diamond Ore",
        "Chest", "Barrel", "Crafting Table", "Furnace",
        "Oak Planks", "Birch Planks", "Spruce Planks"
    )

    /**
     * 扫描假人周围世界状态。
     * @param bot 假人
     * @param commandSender 指令发送者（真实玩家），用于获取准星目标
     */
    fun scan(bot: Player, commandSender: Player? = null): String {
        val level = bot.level()
        val pos = bot.blockPosition()
        val health = bot.health.toInt()
        val maxHealth = bot.maxHealth.toInt()
        val food = bot.foodData.foodLevel

        // 朝向信息
        val yaw = bot.yRot
        val facingDirection = getFacingDirection(yaw)
        val facingChinese = getFacingChinese(facingDirection)

        // 附近重要方块坐标（半径10格）
        val importantBlocks = findImportantBlocks(level, pos, 10)

        // 地形分析
        val terrainInfo = analyzeTerrain(level, pos)

        // 背包内容
        val inventory = buildString {
            for (i in 0 until bot.inventory.containerSize) {
                val stack = bot.inventory.getItem(i)
                if (!stack.isEmpty) {
                    appendLine("  - [${stack.hoverName.string}] x${stack.count}")
                }
            }
        }.ifEmpty { "  （空）" }

        // 主手物品
        val mainHand = bot.mainHandItem
        val mainHandStr = if (mainHand.isEmpty) "空手" else "${mainHand.hoverName.string} x${mainHand.count}"

        // 附近敌对实体
        val hostileEntities = level.getEntities(bot, bot.boundingBox.inflate(16.0)) { true }
            .filter { it.type != EntityType.PLAYER && it.type.category == MobCategory.MONSTER }
            .sortedBy { it.distanceTo(bot) }
            .take(5)
            .joinToString("\n") { entity ->
                val dist = "%.1f".format(entity.distanceTo(bot))
                val entityPos = "(${entity.blockPosition().x}, ${entity.blockPosition().y}, ${entity.blockPosition().z})"
                "  - ${entity.name.string} $entityPos ${dist}格"
            }
            .ifEmpty { "  无" }

        // 时间
        val dayTime = (level.overworldClockTime % 24000).toInt()
        val timeStr = when {
            dayTime < 6000 -> "清晨"
            dayTime < 12000 -> "白天"
            dayTime < 13000 -> "傍晚"
            else -> "夜晚"
        }

        // 指令发送者信息（多玩家上下文）
        val senderInfo = if (commandSender != null) {
            buildSenderInfo(commandSender)
        } else {
            "  无（直接指令）"
        }

        // 假人准星目标
        val botTarget = getCrosshairTarget(bot)

        return """
=== 假人状态 ===
位置：(${pos.x}, ${pos.y}, ${pos.z})
朝向：$facingChinese
血量：$health/$maxHealth
饥饿：$food/20
时间：$timeStr
主手物品：$mainHandStr
准星目标：$botTarget

=== 地形信息 ===
$terrainInfo

=== 附近重要方块（带坐标） ===
$importantBlocks

=== 背包 ===
$inventory

=== 附近敌对实体 ===
$hostileEntities

=== 指令发送者信息 ===
$senderInfo
""".trimIndent()
    }

    /**
     * 构建指令发送者（真实玩家）的信息。
     * 包含位置、朝向、准星目标方块。
     */
    private fun buildSenderInfo(sender: Player): String {
        val pos = sender.blockPosition()
        val yaw = sender.yRot
        val facing = getFacingChinese(getFacingDirection(yaw))
        val health = sender.health.toInt()
        val food = sender.foodData.foodLevel

        // 准星目标方块
        val crosshairTarget = getCrosshairTarget(sender)

        return buildString {
            appendLine("  玩家名：${sender.name.string}")
            appendLine("  位置：(${pos.x}, ${pos.y}, ${pos.z})")
            appendLine("  朝向：$facing")
            appendLine("  血量：$health/20")
            appendLine("  饥饿：$food/20")
            appendLine("  准星目标：$crosshairTarget")
        }.trimEnd()
    }

    /**
     * 获取玩家准星对着的方块信息（公开方法）。
     */
    fun getCrosshairTargetString(player: Player): String = getCrosshairTarget(player)

    /**
     * 获取玩家准星对着的方块信息。
     */
    private fun getCrosshairTarget(player: Player): String {
        val hitResult = player.pick(20.0, 1.0f, false)

        return when (hitResult.type) {
            net.minecraft.world.phys.HitResult.Type.BLOCK -> {
                val blockHit = hitResult as net.minecraft.world.phys.BlockHitResult
                val blockPos = blockHit.blockPos
                val state = player.level().getBlockState(blockPos)
                val blockName = state.block.name.string
                val face = blockHit.direction.name.lowercase()
                "方块 [$blockName] 坐标 (${blockPos.x}, ${blockPos.y}, ${blockPos.z}) 面 $face"
            }
            net.minecraft.world.phys.HitResult.Type.ENTITY -> {
                val entityHit = hitResult as net.minecraft.world.phys.EntityHitResult
                val entity = entityHit.entity
                val dist = "%.1f".format(entity.distanceTo(player))
                "实体 [${entity.name.string}] 距离 ${dist}格"
            }
            else -> "无（看向天空或太远）"
        }
    }

    /**
     * 查找周围重要方块并返回坐标。
     */
    private fun findImportantBlocks(level: Level, center: BlockPos, radius: Int): String {
        val blocks = mutableListOf<String>()
        val blockCounts = mutableMapOf<String, MutableList<BlockPos>>()

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val pos = center.offset(dx, dy, dz)
                    val state = level.getBlockState(pos)
                    val blockName = state.block.name.string

                    if (IMPORTANT_BLOCKS.contains(blockName)) {
                        blockCounts.getOrPut(blockName) { mutableListOf() }.add(pos)
                    }
                }
            }
        }

        for ((name, positions) in blockCounts.entries.sortedByDescending { it.value.size }) {
            val nearest = positions.sortedBy { it.distSqr(center) }.take(3)
            val coordStr = nearest.joinToString(", ") { "(${it.x},${it.y},${it.z})" }
            blocks.add("  - $name x${positions.size}: $coordStr")
        }

        return if (blocks.isEmpty()) "  无" else blocks.joinToString("\n")
    }

    /**
     * 地形分析。
     */
    private fun analyzeTerrain(level: Level, pos: BlockPos): String {
        val info = mutableListOf<String>()

        val below = level.getBlockState(pos.below()).block.name.string
        info.add("脚下：$below")

        val aboveBlocks = mutableListOf<String>()
        for (i in 1..5) {
            val block = level.getBlockState(pos.above(i)).block.name.string
            if (block != "air") {
                aboveBlocks.add("$block(+${i})")
            }
        }
        if (aboveBlocks.isNotEmpty()) {
            info.add("头顶：${aboveBlocks.joinToString(", ")}")
        }

        var groundY = pos.y
        for (y in pos.y downTo pos.y - 10) {
            val block = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (block.isSolidRender) {
                groundY = y
                break
            }
        }
        info.add("地面高度：Y=$groundY")

        var skyVisible = true
        for (y in pos.y + 1..pos.y + 20) {
            val block = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (!block.isAir) {
                skyVisible = false
                break
            }
        }
        info.add("天空可见：${if (skyVisible) "是" else "否"}")

        return info.joinToString("\n")
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

    private fun getFacingChinese(direction: String): String {
        return when (direction) {
            "NORTH" -> "北（Z负方向）"
            "SOUTH" -> "南（Z正方向）"
            "EAST" -> "东（X正方向）"
            "WEST" -> "西（X负方向）"
            else -> direction
        }
    }
}
