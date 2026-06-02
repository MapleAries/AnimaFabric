package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * 线程安全的寻路计算上下文。
 * 参考 Baritone 的 CalculationContext，在寻路开始时捕获世界状态快照，
 * 使寻路可以在后台线程安全运行。
 */
class CalculationContext(
    val level: Level,
    val player: ServerPlayer?,
    /** 是否可以冲刺 */
    val canSprint: Boolean = true,
    /** 是否可以放置方块 */
    val canPlaceBlocks: Boolean = true,
    /** 是否可以跳跃 */
    val canJump: Boolean = true,
    /** 是否启用对角移动 */
    val allowDiagonal: Boolean = true,
    /** 是否启用搭桥 */
    val allowBridging: Boolean = true,
    /** 方块放置代价 */
    val placeBlockCost: Double = ActionCosts.PLACE_BLOCK_PENALTY
) {
    /** 方块状态缓存（避免重复查询） */
    private val blockStateCache = HashMap<Long, BlockState>(256)

    /** 方块可行走缓存 */
    private val canWalkOnCache = HashMap<Long, Boolean>(256)

    /** 方块可通过缓存 */
    private val canWalkThroughCache = HashMap<Long, Boolean>(256)

    /**
     * 获取方块状态（带缓存）。
     */
    fun getBlockState(pos: BlockPos): BlockState {
        val hash = posHash(pos)
        return blockStateCache.getOrPut(hash) {
            level.getBlockState(pos)
        }
    }

    /**
     * 检查方块是否可踩踏（带缓存）。
     */
    fun canWalkOn(pos: BlockPos): Boolean {
        val hash = posHash(pos)
        return canWalkOnCache.getOrPut(hash) {
            BlockClassifier.canWalkOn(level, pos)
        }
    }

    /**
     * 检查方块是否可通过（带缓存）。
     */
    fun canWalkThrough(pos: BlockPos): Boolean {
        val hash = posHash(pos)
        return canWalkThroughCache.getOrPut(hash) {
            BlockClassifier.canWalkThrough(level, pos)
        }
    }

    /**
     * 检查位置是否可行走。
     */
    fun canWalkAt(pos: BlockPos): Boolean {
        return canWalkOn(pos.below()) && canWalkThrough(pos) && canWalkThrough(pos.above())
    }

    /**
     * 检查方块是否危险。
     */
    fun isDangerous(pos: BlockPos): Boolean {
        return BlockClassifier.isDangerous(getBlockState(pos))
    }

    /**
     * 检查区块是否已加载。
     */
    fun isChunkLoaded(pos: BlockPos): Boolean {
        return level.hasChunk(pos.x shr 4, pos.z shr 4)
    }

    /**
     * 清除缓存。
     */
    fun clearCache() {
        blockStateCache.clear()
        canWalkOnCache.clear()
        canWalkThroughCache.clear()
    }

    companion object {
        private fun posHash(pos: BlockPos): Long {
            return (pos.x.toLong() shl 32) or (pos.z.toLong() and 0xFFFFFFFFL) or (pos.y.toLong() shl 16)
        }
    }
}
