package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object WorldPerception {

    fun scan(player: Player): String {
        val level = player.level()
        val pos = player.blockPosition()
        val health = player.health.toInt()
        val maxHealth = player.maxHealth.toInt()
        val food = player.foodData.foodLevel
        val oxygen = player.airSupply
        val maxOxygen = player.maxAirSupply

        // 朝向信息
        val yaw = player.yRot
        val pitch = player.xRot
        val facingDirection = getFacingDirection(yaw)
        val facingChinese = getFacingChinese(facingDirection)

        // 视线前方的方块（射线检测，5格距离）
        val hitResult = player.pick(5.0, 1.0f, false)
        val lookingAt = if (hitResult.type == HitResult.Type.BLOCK) {
            val blockHit = hitResult as BlockHitResult
            val blockPos = blockHit.blockPos
            val blockState = level.getBlockState(blockPos)
            val blockName = blockState.block.name.string
            "(${blockPos.x}, ${blockPos.y}, ${blockPos.z}) $blockName"
        } else {
            "无（5格内没有方块）"
        }

        // 脚下和头顶的方块
        val feetBlock = level.getBlockState(pos).block.name.string
        val headBlock = level.getBlockState(pos.above()).block.name.string
        val belowBlock = level.getBlockState(pos.below()).block.name.string

        // 面前的方块（根据朝向计算，1格距离）
        val frontPos = getFrontBlockPos(pos, facingDirection)
        val frontBlock = level.getBlockState(frontPos).block.name.string

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

        // 附近方块统计（5格半径）
        val blockCounts = mutableMapOf<String, Int>()
        val radius = 5
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
        val nearbyBlocks = blockCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .joinToString("\n") { "  - ${it.key} x${it.value}" }
            .ifEmpty { "  （无）" }

        // 附近实体（16格半径）
        val entities = level.getEntities(player, player.boundingBox.inflate(16.0)) { true }
            .filter { it.type != EntityType.PLAYER }
            .sortedBy { it.distanceTo(player) }
            .take(10)
            .joinToString("\n") { entity ->
                val dist = "%.1f".format(entity.distanceTo(player))
                val hostile = if (entity.type.category == MobCategory.MONSTER) "⚠敌对" else "友好"
                val entityPos = "(${entity.blockPosition().x}, ${entity.blockPosition().y}, ${entity.blockPosition().z})"
                "  - ${entity.name.string} $entityPos ${dist}格 [$hostile]"
            }
            .ifEmpty { "  （无）" }

        // 时间
        val dayTime = (level.overworldClockTime % 24000).toInt()
        val timeStr = when {
            dayTime < 6000 -> "清晨"
            dayTime < 12000 -> "白天"
            dayTime < 13000 -> "傍晚"
            else -> "夜晚"
        }

        // 维度
        val dimension = level.dimension().toString()

        return """
=== 基本信息 ===
位置：(${pos.x}, ${pos.y}, ${pos.z})
维度：$dimension
朝向：$facingChinese（yaw=${"%.1f".format(yaw)}, pitch=${"%.1f".format(pitch)}）
血量：$health/$maxHealth
饥饿：$food/20
氧气：$oxygen/$maxOxygen
时间：$timeStr
主手物品：$mainHandStr

=== 视线信息 ===
看向前方5格：$lookingAt
面前1格方块：(${frontPos.x}, ${frontPos.y}, ${frontPos.z}) $frontBlock
脚下方块：$feetBlock
头顶方块：$headBlock
脚下下方：$belowBlock

=== 背包 ===
$inventory

=== 附近方块（半径5格） ===
$nearbyBlocks

=== 附近实体（半径16格） ===
$entities
""".trimIndent()
    }

    /**
     * 根据 yaw 角度获取朝向方向。
     */
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

    /**
     * 根据朝向获取面前1格的方块位置。
     */
    private fun getFrontBlockPos(pos: BlockPos, direction: String): BlockPos {
        return when (direction) {
            "NORTH" -> pos.north()
            "SOUTH" -> pos.south()
            "EAST" -> pos.east()
            "WEST" -> pos.west()
            else -> pos
        }
    }
}
