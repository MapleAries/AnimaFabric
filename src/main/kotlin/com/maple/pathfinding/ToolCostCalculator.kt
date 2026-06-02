package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * 工具感知的挖掘代价计算器。
 * 根据玩家主手工具和方块类型，计算挖掘的精确 tick 代价。
 */
object ToolCostCalculator {

    /**
     * 计算挖掘方块的 tick 代价（考虑玩家工具）。
     */
    fun getMiningCost(level: Level, pos: BlockPos, player: ServerPlayer? = null): Double {
        val state = level.getBlockState(pos)
        if (state.isAir) return 0.0

        val hardness = state.getDestroySpeed(level, pos)
        if (hardness < 0) return ActionCosts.COST_INF // 不可破坏
        if (hardness == 0f) return 0.0 // 立即破坏

        // 检查是否可以立即破坏
        if (BlockClassifier.isInstantBreak(state)) return 0.0

        // 基础挖掘时间（无工具）
        val baseTicks = hardness * 15.0 // 有工具时的基础时间

        if (player == null) return baseTicks * 2 // 无工具时加倍

        val mainHand = player.mainHandItem
        if (mainHand.isEmpty) return baseTicks * 2

        // 检查工具是否匹配方块类型
        val toolName = mainHand.item.javaClass.simpleName.lowercase()
        val blockName = state.block.name.string.lowercase()

        val isCorrectTool = when {
            BlockClassifier.isDangerous(state) -> false
            toolName.contains("pickaxe") && PICKAXE_KEYWORDS.any { it in blockName } -> true
            toolName.contains("axe") && AXE_KEYWORDS.any { it in blockName } -> true
            toolName.contains("shovel") && SHOVEL_KEYWORDS.any { it in blockName } -> true
            toolName.contains("hoe") && ("leaves" in blockName || "hay" in blockName) -> true
            toolName.contains("shears") && ("leaves" in blockName || "web" in blockName || "wool" in blockName) -> true
            else -> false
        }

        if (!isCorrectTool) return baseTicks * 2

        // 计算工具倍率
        val toolMultiplier = getToolMultiplier(toolName)
        val efficiencyBonus = getEfficiencyBonus(player)

        return (baseTicks / (toolMultiplier * efficiencyBonus)).coerceAtLeast(0.05)
    }

    /**
     * 检查玩家是否有合适的工具来挖掘方块。
     */
    fun canHarvest(player: ServerPlayer, state: BlockState): Boolean {
        val blockName = state.block.name.string.lowercase()
        val mainHand = player.mainHandItem
        val toolName = if (mainHand.isEmpty) "" else mainHand.item.javaClass.simpleName.lowercase()

        // 大多数方块都可以用手挖掘
        val needsTool = PICKAXE_KEYWORDS.any { it in blockName } ||
                       AXE_KEYWORDS.any { it in blockName } ||
                       SHOVEL_KEYWORDS.any { it in blockName }

        if (!needsTool) return true

        return when {
            PICKAXE_KEYWORDS.any { it in blockName } -> toolName.contains("pickaxe")
            AXE_KEYWORDS.any { it in blockName } -> toolName.contains("axe")
            SHOVEL_KEYWORDS.any { it in blockName } -> toolName.contains("shovel")
            else -> true
        }
    }

    // ========== 内部辅助 ==========

    /** 需要斧头的方块关键词 */
    private val AXE_KEYWORDS = setOf(
        "log", "planks", "fence", "gate", "bookshelf", "chest",
        "crafting_table", "barrel", "composter", "campfire"
    )

    /** 需要镐的方块关键词 */
    private val PICKAXE_KEYWORDS = setOf(
        "stone", "cobblestone", "granite", "diorite", "andesite",
        "deepslate", "sandstone", "iron_ore", "gold_ore", "diamond_ore",
        "emerald_ore", "lapis_ore", "redstone_ore", "coal_ore",
        "obsidian", "bricks", "stone_bricks", "nether_bricks",
        "glass", "enchanting_table", "anvil", "lantern",
        "iron_block", "gold_block", "diamond_block", "emerald_block"
    )

    /** 需要锹的方块关键词 */
    private val SHOVEL_KEYWORDS = setOf(
        "dirt", "grass_block", "podzol", "mycelium", "farmland",
        "clay", "gravel", "sand", "red_sand", "soul_sand", "snow_block", "mud"
    )

    /**
     * 获取工具倍率。
     * 木2x, 石4x, 铁6x, 钻石8x, 下界合金9x
     */
    private fun getToolMultiplier(toolName: String): Double {
        return when {
            "netherite" in toolName -> 9.0
            "diamond" in toolName -> 8.0
            "iron" in toolName -> 6.0
            "stone" in toolName -> 4.0
            "gold" in toolName -> 12.0 // 金工具最快但不耐用
            "wood" in toolName -> 2.0
            else -> 1.0
        }
    }

    /**
     * 获取效率附魔加成。
     * 简化版：通过 EnchantmentHelper 检查。
     */
    private fun getEfficiencyBonus(player: ServerPlayer): Double {
        // 简化实现：假设有效率附魔时给予加成
        // 完整实现需要使用 EnchantmentHelper.getBlockEfficiency(player)
        // 但该方法在不同 MC 版本中 API 不同，暂时返回 1.0
        return 1.0
    }
}
