package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

object WorldPerception {

    // 重要方块列表
    private val IMPORTANT_BLOCKS = setOf(
        "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log",
        "stone", "cobblestone", "coal_ore", "iron_ore", "gold_ore", "diamond_ore",
        "chest", "barrel", "crafting_table", "furnace"
    )

    fun scan(player: Player): String {
        val level = player.level()
        val pos = player.blockPosition()
        val health = player.health.toInt()
        val maxHealth = player.maxHealth.toInt()
        val food = player.foodData.foodLevel

        // 朝向信息
        val yaw = player.yRot
        val facingDirection = getFacingDirection(yaw)
        val facingChinese = getFacingChinese(facingDirection)

        // 1. 高度图感知 - 瞬间获取周围地形高度
        val heightmap = scanHeightmap(level, pos, 10)

        // 2. 多方向射线检测 - 360° 感知
        val raycastResults = multiRaycast(level, player, 10)

        // 3. 重要方块坐标
        val importantBlocks = findImportantBlocks(level, pos, 10)

        // 4. 地形分析
        val terrainInfo = analyzeTerrain(level, pos, heightmap)

        // 5. 背包和主手
        val inventory = buildString {
            for (i in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(i)
                if (!stack.isEmpty) {
                    appendLine("  - [${stack.hoverName.string}] x${stack.count}")
                }
            }
        }.ifEmpty { "  （空）" }

        val mainHand = player.mainHandItem
        val mainHandStr = if (mainHand.isEmpty) "空手" else "${mainHand.hoverName.string} x${mainHand.count}"

        // 6. 附近实体
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

        return """
=== 基本信息 ===
位置：(${pos.x}, ${pos.y}, ${pos.z})
朝向：$facingChinese
血量：$health/$maxHealth
饥饿：$food/20
时间：$timeStr
主手物品：$mainHandStr

=== 360° 射线检测（10格） ===
$raycastResults

=== 地形分析 ===
$terrainInfo

=== 附近重要方块（带坐标） ===
$importantBlocks

=== 背包 ===
$inventory

=== 附近实体 ===
$entities
""".trimIndent()
    }

    /**
     * 高度图扫描 - 获取周围地形高度。
     * 使用 Minecraft 内置的高度图，效率极高。
     */
    private fun scanHeightmap(level: Level, center: BlockPos, radius: Int): Map<Pair<Int, Int>, Int> {
        val heights = mutableMapOf<Pair<Int, Int>, Int>()

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val x = center.x + dx
                val z = center.z + dz
                val chunk = level.getChunk(x shr 4, z shr 4)
                val height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15)
                heights[Pair(dx, dz)] = height
            }
        }

        return heights
    }

    /**
     * 多方向射线检测 - 360° 感知。
     * 向 8 个水平方向 + 上下各发射一条射线。
     */
    private fun multiRaycast(level: Level, player: Player, distance: Int): String {
        val eyePos = player.eyePosition
        val results = mutableListOf<String>()

        // 8 个水平方向
        val directions = listOf(
            "北" to Vec3(0.0, 0.0, -1.0),
            "南" to Vec3(0.0, 0.0, 1.0),
            "东" to Vec3(1.0, 0.0, 0.0),
            "西" to Vec3(-1.0, 0.0, 0.0),
            "东北" to Vec3(0.7, 0.0, -0.7),
            "东南" to Vec3(0.7, 0.0, 0.7),
            "西北" to Vec3(-0.7, 0.0, -0.7),
            "西南" to Vec3(-0.7, 0.0, 0.7)
        )

        for ((name, dir) in directions) {
            val endPos = eyePos.add(dir.scale(distance.toDouble()))
            val hitResult = level.clip(ClipContext(
                eyePos, endPos,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player
            ))

            if (hitResult.type == HitResult.Type.BLOCK) {
                val blockPos = hitResult.blockPos
                val blockName = level.getBlockState(blockPos).block.name.string
                val hitDistance = eyePos.distanceTo(hitResult.location)
                results.add("  $name: $blockName (${blockPos.x},${blockPos.y},${blockPos.z}) ${"%.1f".format(hitDistance)}格")
            } else {
                results.add("  $name: 无 (${distance}格内)")
            }
        }

        // 向上检测
        val upEnd = eyePos.add(0.0, distance.toDouble(), 0.0)
        val upHit = level.clip(ClipContext(eyePos, upEnd, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        if (upHit.type == HitResult.Type.BLOCK) {
            val blockPos = upHit.blockPos
            val blockName = level.getBlockState(blockPos).block.name.string
            results.add("  上: $blockName (${blockPos.x},${blockPos.y},${blockPos.z})")
        }

        // 向下检测
        val downEnd = eyePos.add(0.0, -distance.toDouble(), 0.0)
        val downHit = level.clip(ClipContext(eyePos, downEnd, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        if (downHit.type == HitResult.Type.BLOCK) {
            val blockPos = downHit.blockPos
            val blockName = level.getBlockState(blockPos).block.name.string
            val hitDistance = eyePos.distanceTo(downHit.location)
            results.add("  下: $blockName (${blockPos.x},${blockPos.y},${blockPos.z}) ${"%.1f".format(hitDistance)}格")
        }

        return results.joinToString("\n")
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

        return if (blocks.isEmpty()) "  （无）" else blocks.joinToString("\n")
    }

    /**
     * 地形分析 - 使用高度图数据。
     */
    private fun analyzeTerrain(level: Level, pos: BlockPos, heightmap: Map<Pair<Int, Int>, Int>): String {
        val info = mutableListOf<String>()

        // 脚下方块
        val below = level.getBlockState(pos.below()).block.name.string
        info.add("脚下：$below")

        // 头顶上方方块
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

        // 地面高度（从高度图获取）
        val currentHeight = heightmap[Pair(0, 0)] ?: pos.y
        info.add("地面高度：Y=$currentHeight")

        // 天空可见性
        var skyVisible = true
        for (y in pos.y + 1..pos.y + 20) {
            val block = level.getBlockState(BlockPos(pos.x, y, pos.z))
            if (!block.isAir) {
                skyVisible = false
                break
            }
        }
        info.add("天空可见：${if (skyVisible) "是" else "否"}")

        // 周围地形高度变化
        val heights = heightmap.values
        val minHeight = heights.minOrNull() ?: pos.y
        val maxHeight = heights.maxOrNull() ?: pos.y
        if (maxHeight - minHeight > 3) {
            info.add("地形起伏：Y=$minHeight ~ $maxHeight（高差${maxHeight - minHeight}格）")
        }

        // 周围是否有树木
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
