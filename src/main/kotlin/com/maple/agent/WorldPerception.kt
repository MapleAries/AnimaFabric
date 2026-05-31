package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

object WorldPerception {

    fun scan(player: Player): String {
        val level = player.level()
        val pos = player.blockPosition()
        val health = player.health.toInt()
        val maxHealth = player.maxHealth.toInt()
        val food = player.foodData.foodLevel
        val oxygen = player.airSupply
        val maxOxygen = player.maxAirSupply

        // 背包内容
        val inventory = buildString {
            for (i in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(i)
                if (!stack.isEmpty) {
                    appendLine("  - [${stack.hoverName.string}] x${stack.count}")
                }
            }
        }.ifEmpty { "  （空）" }

        // 附近方块统计（3格半径）
        val blockCounts = mutableMapOf<String, Int>()
        val radius = 3
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val bp = pos.offset(dx, dy, dz)
                    val state = level.getBlockState(bp)
                    if (!state.isAir) {
                        val name = getBlockName(state)
                        blockCounts[name] = (blockCounts[name] ?: 0) + 1
                    }
                }
            }
        }
        val nearbyBlocks = blockCounts.entries
            .sortedByDescending { it.value }
            .take(15)
            .joinToString("\n") { "  - ${it.key} x${it.value}" }
            .ifEmpty { "  （无）" }

        // 附近实体（16格半径）
        val entities = level.getEntities(player, player.boundingBox.inflate(16.0)) { true }
            .filter { it.type != EntityType.PLAYER }
            .sortedBy { it.distanceTo(player) }
            .take(10)
            .joinToString("\n") { entity ->
                val dist = "%.1f".format(entity.distanceTo(player))
                val hostile = if (isHostile(entity.type)) "⚠敌对" else "友好"
                "  - ${entity.name.string} ($dist格) [$hostile]"
            }
            .ifEmpty { "  （无）" }

        // 时间
        val dayTime = level.dayTime % 24000
        val timeStr = when {
            dayTime < 6000 -> "清晨"
            dayTime < 12000 -> "白天"
            dayTime < 13000 -> "傍晚"
            else -> "夜晚"
        }

        return """
位置：(${pos.x}, ${pos.y}, ${pos.z})
血量：$health/$maxHealth
饥饿：$food/20
氧气：$oxygen/$maxOxygen（如果在水下）
时间：$timeStr

背包：
$inventory

附近方块（半径3格）：
$nearbyBlocks

附近实体（半径16格）：
$entities
""".trimIndent()
    }

    private fun getBlockName(state: BlockState): String {
        return state.block.name.string
    }

    private fun isHostile(type: EntityType<*>): Boolean {
        return type.category == net.minecraft.world.entity.MobCategory.MONSTER
    }
}
