package com.maple.pathfinding

/**
 * 基于 Minecraft 物理的精确移动代价（单位：tick）。
 * 参考 Baritone 的 ActionCosts 实现。
 */
object ActionCosts {

    // ========== 基础移动代价（tick/格）==========

    /** 行走速度 4.317 格/秒 → 20/4.317 = 4.633 tick/格 */
    const val WALK_ONE_BLOCK = 4.633

    /** 冲刺速度 5.612 格/秒 → 20/5.612 = 3.564 tick/格 */
    const val SPRINT_ONE_BLOCK = 3.564

    /** 潜行速度 1.3 格/秒 → 20/1.3 = 15.385 tick/格 */
    const val SNEAK_ONE_BLOCK = 15.385

    /** 水中速度 2.2 格/秒 → 20/2.2 = 9.091 tick/格 */
    const val WATER_ONE_BLOCK = 9.091

    /** 梯子上升 2.35 格/秒 → 20/2.35 = 8.511 tick/格 */
    const val LADDER_UP_ONE = 8.511

    /** 梯子下降速度 */
    const val LADDER_DOWN_ONE = 6.7

    // ========== 对角移动 ==========

    /** 对角行走 = 行走 × √2 */
    const val WALK_DIAGONAL = WALK_ONE_BLOCK * 1.414

    /** 对角冲刺 = 冲刺 × √2 */
    const val SPRINT_DIAGONAL = SPRINT_ONE_BLOCK * 1.414

    // ========== 跳跃代价 ==========

    /** 跳跃额外代价（相对于水平行走） */
    const val JUMP_PENALTY = 2.0

    /** 跳跃 + 冲刺额外代价 */
    const val SPRINT_JUMP_PENALTY = 1.5

    // ========== 下落代价 ==========
    // Minecraft 下落物理: 速度 v(t) = 0.98^t * v0 + 3.92 * (1 - 0.98^t) / 0.02
    // 简化计算：下落 N 格需要的 tick 数

    /** 下落 1 格的 tick 代价 */
    const val FALL_1_BLOCK = 4.0

    /** 下落 2 格的 tick 代价 */
    const val FALL_2_BLOCKS = 5.0

    /** 下落 3 格的 tick 代价 */
    const val FALL_3_BLOCKS = 6.0

    /** 安全下落高度（无水桶） */
    const val SAFE_FALL_HEIGHT = 3

    /** 水桶下落最大高度 */
    const val BUCKET_FALL_HEIGHT = 23

    // ========== 方块交互代价 ==========

    /** 放置一个方块的额外代价（潜行 + 右键） */
    const val PLACE_BLOCK_PENALTY = SNEAK_ONE_BLOCK + 2.0

    /** 跳跃放置的额外代价 */
    const val JUMP_PLACE_PENALTY = JUMP_PENALTY + PLACE_BLOCK_PENALTY

    // ========== 无穷大 ==========

    /** 不可达代价 */
    const val COST_INF = 1_000_000.0

    // ========== 辅助计算 ==========

    /**
     * 计算下落 N 格的 tick 代价。
     * 基于 Minecraft 重力物理近似。
     */
    fun fallCost(blocks: Int): Double {
        return when {
            blocks <= 0 -> 0.0
            blocks == 1 -> FALL_1_BLOCK
            blocks == 2 -> FALL_2_BLOCKS
            blocks == 3 -> FALL_3_BLOCKS
            else -> FALL_3_BLOCKS + (blocks - 3) * 1.0 // 近似：每多 1 格约 1 tick
        }
    }

    /**
     * 计算挖掘方块的 tick 代价。
     * 简化版：根据方块硬度估算。
     * @param hardness 方块硬度（-1 = 不可破坏）
     * @param canHarvest 是否有合适工具
     */
    fun miningCost(hardness: Float, canHarvest: Boolean): Double {
        if (hardness < 0) return COST_INF // 不可破坏
        if (hardness == 0f) return 0.0 // 立即破坏（如火把）

        // 基础公式：破坏 tick = 硬度 × 30（无工具）或 硬度 × 15（有工具）
        val multiplier = if (canHarvest) 15.0 else 30.0
        return (hardness * multiplier).coerceAtLeast(1.0)
    }
}
