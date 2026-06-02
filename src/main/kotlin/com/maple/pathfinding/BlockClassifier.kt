package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState

/**
 * 方块分类系统。
 * 基于方块名称和属性进行分类，兼容不同 MC 版本。
 */
object BlockClassifier {

    // ========== 方块名称关键词集合 ==========

    /** 危险方块 */
    private val DANGEROUS_KEYWORDS = setOf("lava", "fire", "cactus", "magma", "soul_fire", "powder_snow", "berry_bush")

    /** 可攀爬方块 */
    private val CLIMBABLE_KEYWORDS = setOf("ladder", "vine", "scaffolding")

    /** 水 */
    private val WATER_KEYWORDS = setOf("water")

    /** 岩浆 */
    private val LAVA_KEYWORDS = setOf("lava")

    /** 可立即破坏的方块 */
    private val INSTANT_BREAK_KEYWORDS = setOf(
        "torch", "redstone_torch", "flower", "tall_grass", "grass", "fern",
        "dead_bush", "dandelion", "poppy", "sapling", "mushroom",
        "sugar_cane", "lever", "button", "pressure_plate", "rail",
        "tripwire", "snow_layer", "moss_carpet", "small_amethyst_bud"
    )

    /** 需要斧头 */
    private val AXE_KEYWORDS = setOf(
        "log", "planks", "fence", "gate", "bookshelf", "chest",
        "crafting_table", "barrel", "composter", "loom", "lectern",
        "campfire", "bee_nest", "beehive", "jack_o_lantern"
    )

    /** 需要镐 */
    private val PICKAXE_KEYWORDS = setOf(
        "stone", "cobblestone", "granite", "diorite", "andesite",
        "deepslate", "sandstone", "iron_ore", "gold_ore", "diamond_ore",
        "emerald_ore", "lapis_ore", "redstone_ore", "coal_ore", "copper_ore",
        "obsidian", "netherite", "bricks", "stone_bricks", "nether_bricks",
        "end_stone", "glass", "enchanting_table", "anvil", "grindstone",
        "stonecutter", "bell", "lantern", "iron_block", "gold_block",
        "diamond_block", "emerald_block", "coal_block", "lapis_block",
        "redstone_block", "smooth_stone"
    )

    /** 需要锹 */
    private val SHOVEL_KEYWORDS = setOf(
        "dirt", "grass_block", "podzol", "mycelium", "coarse_dirt",
        "farmland", "clay", "gravel", "sand", "red_sand",
        "soul_sand", "soul_soil", "snow_block", "mud", "packed_mud"
    )

    /** 可踩踏但不完全固体的方块 */
    private val WALKABLE_KEYWORDS = setOf(
        "slab", "stairs", "carpet", "soul_sand", "beacon",
        "enchanting_table", "brewing_stand", "chest", "trapped_chest",
        "hopper", "anvil", "grindstone", "stonecutter"
    )

    // ========== 主要分类方法 ==========

    /**
     * 检查方块是否可以踩踏。
     */
    fun canWalkOn(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.isAir) return false

        // 固体方块
        if (state.isSolidRender) return true

        val name = state.block.name.string.lowercase()

        // 半砖（上半砖可踩踏）
        if ("slab" in name) {
            // 检查是否是上半砖或双层
            val prop = state.properties.find { it.name == "type" }
            if (prop != null) {
                val value = state.getValue(prop).toString()
                return value == "top" || value == "double"
            }
            return true // 无法判断类型时默认可踩踏
        }

        // 台阶
        if ("stairs" in name) return true

        // 地毯（需要下方有固体）
        if ("carpet" in name) {
            return canWalkOn(level, pos.below())
        }

        // 雪层（至少 7 层）
        if ("snow_layer" in name || "snow" in name && "block" !in name) {
            val prop = state.properties.find { it.name == "layers" }
            if (prop != null) {
                val layers = state.getValue(prop) as? Int ?: 1
                return layers >= 7
            }
            return false
        }

        // 其他可踩踏方块
        return WALKABLE_KEYWORDS.any { it in name }
    }

    /**
     * 检查方块是否可以通过。
     */
    fun canWalkThrough(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.isAir) return true

        val block = state.block
        val name = block.name.string.lowercase()

        // 固体方块不可通过
        if (state.isSolidRender) return false

        // 水
        if (WATER_KEYWORDS.any { it in name }) return true

        // 草、花、蕨类等植被
        if (block is BushBlock) return true

        // 火把、红石火把
        if (block is TorchBlock) return true

        // 铁轨
        if (block is BaseRailBlock) return true

        // 按钮、拉杆
        if (block is ButtonBlock || block is LeverBlock) return true

        // 压力板
        if (block is PressurePlateBlock) return true

        // 告示牌
        if ("sign" in name) return true

        // 红石线、中继器、比较器
        if (block is RedStoneWireBlock || block is RepeaterBlock || block is ComparatorBlock) return true

        // 门
        if (block is DoorBlock) return true

        // 栅栏门
        if (block is FenceGateBlock) return true

        // 活板门（打开时）
        if (block is TrapDoorBlock) {
            val prop = state.properties.find { it.name == "open" }
            if (prop != null) return state.getValue(prop) as? Boolean ?: false
        }

        // 蜘蛛网、浆果丛
        if ("cobweb" in name || "web" in name || "berry" in name) return true

        // 半砖（下半砖可通过头部空间）
        if ("slab" in name) {
            val prop = state.properties.find { it.name == "type" }
            if (prop != null) {
                val value = state.getValue(prop).toString()
                return value == "bottom"
            }
        }

        // 危险方块不可通过
        if (isDangerous(state)) return false

        // 默认：非固体可通过
        return !state.isSolidRender
    }

    /**
     * 检查方块是否危险。
     */
    fun isDangerous(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return DANGEROUS_KEYWORDS.any { it in name }
    }

    /**
     * 检查方块是否可攀爬。
     */
    fun isClimbable(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return CLIMBABLE_KEYWORDS.any { it in name }
    }

    /**
     * 检查方块是否是水。
     */
    fun isWater(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return WATER_KEYWORDS.any { it in name }
    }

    /**
     * 检查方块是否是岩浆。
     */
    fun isLava(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return LAVA_KEYWORDS.any { it in name }
    }

    /**
     * 检查方块是否可被替换。
     */
    fun isReplaceable(state: BlockState): Boolean {
        if (state.isAir) return true
        val name = state.block.name.string.lowercase()
        if (WATER_KEYWORDS.any { it in name }) return true
        if ("snow_layer" in name) {
            val prop = state.properties.find { it.name == "layers" }
            if (prop != null) {
                val layers = state.getValue(prop) as? Int ?: 1
                return layers == 1
            }
        }
        return false
    }

    /**
     * 检查方块是否可以立即破坏。
     */
    fun isInstantBreak(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return INSTANT_BREAK_KEYWORDS.any { it in name }
    }
}
