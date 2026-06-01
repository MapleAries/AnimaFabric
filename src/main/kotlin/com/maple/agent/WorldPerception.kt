package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object WorldPerception {

    // 重要方块列表（需要记录坐标的）
    private val IMPORTANT_BLOCKS = setOf(
        "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log",
        "stone", "cobblestone", "coal_ore", "iron_ore", "gold_ore", "diamond_ore",
        "chest", "barrel", "crafting_table", "furnace",
        "water", "lava"
    )

    fun scan(player: Player): String {
        val level = player.level()
        val pos = player.blockPosition()
        val health = player.health.toInt()
        val maxHealth = player.maxHealth.toInt()
        val food = player.foodData.foodLevel

        // 朝向信息
        val yaw = player.yRot
        val pitch = player.xRot
        val facingDirection = getFacingDirection(yaw)
        val facingChinese = getFacingChinese(facingDirection)

        // 视线检测（10格）
        val hitResult = player.pick(10.0, 1.0f, false)
        val lookingAt = if (hitResult.type == HitResult.Type.BLOCK) {
            val blockHit = hitResult as BlockHitResult
            val blockPos = blockHit.blockPos
            val blockState = level.getBlockState(blockPos)
            val blockName = blockState.block.name.string
            "(${blockPos.x}, ${blockPos.y}, ${blockPos.z}) $blockName"
        } else {
            "无（10格内没有方块）"
        }

        // 周围重要方块坐标（半径10格）
        val importantBlocks = findImportantBlocks(level, pos, 10)

        // 地形分析
        val terrainInfo = analyzeTerrain(level, pos)

        // 背包内容
        val inventory = buildString {
            for (i in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(i)
                if (!stack.isEmpty) {
                    appendLine("  - [${stack.hoverName.string}] x${stack.count}")
                }
            }
        }.ifEmpty { "  （空）" }

        // 主手物品
        val mainHand = player.mainHandItem
        val mainHandStr = if (mainHand.isEmpty) "空手" else "${mainHand.hoverName.string} x${mainHand.count}"

        // 附近实体（详细信息）
        val entities = level.getEntities(player, player.boundingBox.inflate(16.0)) { true }
            .filter { it.type != EntityType.PLAYER }
            .sortedBy { it.distanceTo(player) }
            .take(10)
            .joinToString("\n") { entity ->
                val dist = "%.1f".format(entity.distanceTo(player))
                val hostile = if (entity.type.category == MobCategory.MONSTER) "⚠敌对" else "友好"
                val entityPos = "(${entity.blockPosition().x}, ${entity.blockPosition().y}, ${entity.blockPosition().z})"
                val entityName = entity.name.string
                "  - $entityName $entityPos ${dist}格 [$hostile]"
            }
            .ifEmpty { "  （无）" }

        // 时间和维度
        val dayTime = (level.overworldClockTime % 24000).toInt()
        val timeStr = when {
            dayTime < 6000 -> "清晨"
            dayTime < 12000 -> "白天"
            dayTime < 13000 -> "傍晚"
            else -> "夜晚"
        }
        val dimension = level.dimension().toString()

        return """
=== 基本信息 ===
位置：(${pos.x}, ${pos.y}, ${pos.z})
维度：$dimension
朝向：$facingChinese（yaw=${"%.1f".format(yaw)}）
血量：$health/$maxHealth
饥饿：$food/20
时间：$timeStr
主手物品：$mainHandStr

=== 视线信息（10格） ===
看向前方：$lookingAt

=== 地形分析 ===
$terrainInfo

=== 附近重要方块（带坐标） ===
$importantBlocks

=== 背包 ===
$inventory

=== 附近实体（半径16格） ===
$entities
""".trimIndent()
    }

    /**
     * 查找周围重要方块并返回坐标。
     */
    private fun findImportantBlocks(level: net.minecraft.world.level.Level, center: BlockPos, radius: Int): String {
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

        // 对每种方块，只返回最近的几个坐标
        for ((name, positions) in blockCounts.entries.sortedByDescending { it.value.size }) {
            val nearest = positions.sortedBy { it.distSqr(center) }.take(3)
            val coordStr = nearest.joinToString(", ") { "(${it.x},${it.y},${it.z})" }
            blocks.add("  - $name x${positions.size}: $coordStr")
        }

        return if (blocks.isEmpty()) "  （无）" else blocks.joinToString("\n")
    }

    /**
     * 分析地形结构。
     */
    private fun analyzeTerrain(level: net.minecraft.world.level.Level, pos: BlockPos): String {
        val info = mutableListOf<String>()

        // 脚下方块
        val below = level.getBlockState(pos.below()).block.name.string
        info.add("脚下：$below")

        // 头顶上方5格
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

        // 地面高度（向下找固体方块）
        var groundY = pos.y
        for (y in pos.y downTo pos.y - 10) {
            val block = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (block.isSolidRender) {
                groundY = y
                break
            }
        }
        info.add("地面高度：Y=$groundY")

        // 天空可见性（向上找空气）
        var skyVisible = true
        for (y in pos.y + 1..pos.y + 20) {
            val block = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (!block.isAir) {
                skyVisible = false
                break
            }
        }
        info.add("天空可见：${if (skyVisible) "是" else "否"}")

        // 周围是否有树（检查上方是否有树叶）
        var hasTree = false
        for (dx in -3..3) {
            for (dz in -3..3) {
                for (dy in 1..5) {
                    val block = level.getBlockState(pos.offset(dx, dy, dz)).block.name.string
                    if (block.contains("leaves") || block.contains("log")) {
                        hasTree = true
                        break
                    }
                }
                if (hasTree) break
            }
            if (hasTree) break
        }
        if (hasTree) info.add("附近有树木")

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
