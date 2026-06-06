package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * 移动类型枚举，定义所有可能的移动方式及其代价计算。
 * 参考 Baritone 的 Moves 系统。
 */
enum class MovementType(
    /** 目标偏移（相对于当前位置） */
    val dx: Int, val dy: Int, val dz: Int,
    /** 是否需要动态计算目标（如掉落可能多格） */
    val dynamicY: Boolean = false
) {
    // ========== 水平移动 ==========
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),

    // ========== 对角移动 ==========
    NORTH_EAST(1, 0, -1),
    NORTH_WEST(-1, 0, -1),
    SOUTH_EAST(1, 0, 1),
    SOUTH_WEST(-1, 0, 1),

    // ========== 上升移动 ==========
    ASCEND_NORTH(0, 1, -1),
    ASCEND_SOUTH(0, 1, 1),
    ASCEND_EAST(1, 1, 0),
    ASCEND_WEST(-1, 1, 0),

    // ========== 下降移动（可能动态掉落多格）==========
    DESCEND_NORTH(0, -1, -1, dynamicY = true),
    DESCEND_SOUTH(0, -1, 1, dynamicY = true),
    DESCEND_EAST(1, -1, 0, dynamicY = true),
    DESCEND_WEST(-1, -1, 0, dynamicY = true),

    // ========== 垂直移动 ==========
    PILLAR(0, 1, 0),   // 跳跃 + 放置方块向上
    DOWNWARD(0, -1, 0); // 挖掘下方方块

    companion object {
        /** 水平方向（4 方向） */
        val HORIZONTAL = listOf(NORTH, SOUTH, EAST, WEST)

        /** 对角方向（4 方向） */
        val DIAGONAL = listOf(NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST)

        /** 上升方向（4 方向） */
        val ASCEND = listOf(ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST)

        /** 下降方向（4 方向） */
        val DESCEND = listOf(DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST)

        /** 所有水平 + 对角移动 */
        val ALL_HORIZONTAL = HORIZONTAL + DIAGONAL

        /** 当前 ActionExecutor 可以可靠执行的移动 */
        val EXECUTABLE = HORIZONTAL + DIAGONAL + ASCEND + DESCEND

        /** 所有移动 */
        val ALL = entries.toList()
    }
}

/**
 * 移动代价计算器。
 * 根据世界状态计算每种移动的精确代价。
 */
object MovementCostCalculator {

    /**
     * 计算水平移动的代价。
     */
    fun horizontalCost(
        level: Level,
        from: BlockPos,
        move: MovementType,
        canSprint: Boolean
    ): Double {
        val to = from.offset(move.dx, move.dy, move.dz)

        // 检查目标位置是否可行走
        if (!canWalkAt(level, to)) return ActionCosts.COST_INF

        // 检查路径上是否有障碍（对角移动需要检查两个角落）
        if (move in MovementType.DIAGONAL) {
            val corner1 = from.offset(move.dx, 0, 0)
            val corner2 = from.offset(0, 0, move.dz)
            if (!canWalkThrough(level, corner1) && !canWalkThrough(level, corner2)) {
                return ActionCosts.COST_INF // 两个角落都堵住
            }
            // 对角移动代价
            return if (canSprint) ActionCosts.SPRINT_DIAGONAL else ActionCosts.WALK_DIAGONAL
        }

        // 水平移动代价
        return if (canSprint) ActionCosts.SPRINT_ONE_BLOCK else ActionCosts.WALK_ONE_BLOCK
    }

    /**
     * 计算上升移动的代价（走上去一格）。
     */
    fun ascendCost(
        level: Level,
        from: BlockPos,
        move: MovementType
    ): Double {
        val to = from.offset(move.dx, move.dy, move.dz)
        val stepOn = from.offset(move.dx, 0, move.dz) // 中间位置（脚下）

        // 目标位置上方需要有空间
        if (!canWalkThrough(level, to) || !canWalkThrough(level, to.above())) {
            return ActionCosts.COST_INF
        }

        // 中间位置需要是可踩踏的方块
        if (!canWalkOn(level, stepOn)) {
            return ActionCosts.COST_INF
        }

        // 上升代价 = 行走 + 跳跃惩罚
        return ActionCosts.WALK_ONE_BLOCK + ActionCosts.JUMP_PENALTY
    }

    /**
     * 计算下降移动的代价（走下一格或掉落多格）。
     * 如果下方是空气，会动态计算掉落距离。
     */
    fun descendCost(
        level: Level,
        from: BlockPos,
        move: MovementType
    ): Double {
        val stepOn = from.offset(move.dx, 0, move.dz) // 中间位置

        // 中间位置需要可行走
        if (!canWalkOn(level, stepOn)) {
            return ActionCosts.COST_INF
        }

        val to = from.offset(move.dx, move.dy, move.dz)
        if (!canWalkAt(level, to)) return ActionCosts.COST_INF

        return ActionCosts.WALK_ONE_BLOCK + ActionCosts.FALL_1_BLOCK
    }

    /**
     * 计算掉落的代价（动态掉落多格）。
     */
    fun fallCost(
        level: Level,
        from: BlockPos,
        move: MovementType
    ): Double {
        val targetX = from.x + move.dx
        val targetZ = from.z + move.dz

        // 向下搜索着陆点
        var landY = from.y - 1
        val minY = -64 // MC 世界最低高度
        while (landY > minY) {
            val pos = BlockPos(targetX, landY, targetZ)
            if (canWalkOn(level, pos)) break
            landY--
        }

        val fallDistance = from.y - landY - 1
        if (fallDistance <= 0) return ActionCosts.COST_INF // 没有着陆点

        // 检查着陆点上方是否有空间
        val landPos = BlockPos(targetX, landY + 1, targetZ)
        if (!canWalkThrough(level, landPos) || !canWalkThrough(level, landPos.above())) {
            return ActionCosts.COST_INF
        }

        // 检查是否超过安全下落高度
        if (fallDistance > ActionCosts.BUCKET_FALL_HEIGHT) {
            return ActionCosts.COST_INF // 太高，即使有水桶也不行
        }

        // 掉落代价 = 水平移动 + 掉落物理代价
        return ActionCosts.WALK_ONE_BLOCK + ActionCosts.fallCost(fallDistance)
    }

    /**
     * 计算垂直上升的代价（跳跃 + 放置方块）。
     */
    fun pillarCost(
        level: Level,
        from: BlockPos
    ): Double {
        val to = from.above()
        if (!canWalkThrough(level, to) || !canWalkThrough(level, to.above())) {
            return ActionCosts.COST_INF
        }
        return ActionCosts.JUMP_PLACE_PENALTY
    }

    /**
     * 计算垂直下降的代价（挖掘下方方块）。
     */
    fun downwardCost(
        level: Level,
        from: BlockPos
    ): Double {
        val below = from.below()
        val state = level.getBlockState(below)

        // 不可破坏的方块（基岩等）
        val hardness = state.getDestroySpeed(level, below)
        if (hardness < 0) return ActionCosts.COST_INF

        val miningCost = ActionCosts.miningCost(hardness, true)
        return miningCost + ActionCosts.FALL_1_BLOCK
    }

    // ========== 方块检查辅助方法 ==========

    /**
     * 检查位置是否可行走（脚下是固体，脚部和头部是非固体）。
     */
    fun canWalkAt(level: Level, pos: BlockPos): Boolean {
        return BlockClassifier.canWalkOn(level, pos.below()) &&
               BlockClassifier.canWalkThrough(level, pos) &&
               BlockClassifier.canWalkThrough(level, pos.above())
    }

    /**
     * 检查方块是否可以踩踏。
     */
    fun canWalkOn(level: Level, pos: BlockPos): Boolean = BlockClassifier.canWalkOn(level, pos)

    /**
     * 检查方块是否可以通过。
     */
    fun canWalkThrough(level: Level, pos: BlockPos): Boolean = BlockClassifier.canWalkThrough(level, pos)

    /**
     * 检查方块是否危险。
     */
    fun isDangerous(state: BlockState): Boolean = BlockClassifier.isDangerous(state)
}
