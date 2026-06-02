package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * 增强版 A* 寻路算法。
 *
 * 改进点（参考 Baritone）：
 * - 18 种移动类型 + tick 精确代价
 * - 超时机制 + 多系数路径跟踪
 * - 回避系统（回退回避 + 生物回避）
 * - 区块边界感知
 * - Long hash 优化节点存储
 */
object AStarPathfinder {

    // ========== 配置常量 ==========

    private const val PRIMARY_TIMEOUT_MS = 3000L
    private const val FAILURE_TIMEOUT_MS = 5000L
    private const val MIN_PATH_LENGTH = 3
    private val COEFFICIENTS = doubleArrayOf(1.5, 2.0, 2.5, 3.0, 4.0, 5.0)

    /** 回退路径代价倍增系数 */
    private const val BACKTRACK_PENALTY = 1.5

    /** 生物回避半径（格） */
    private const val MOB_AVOIDANCE_RADIUS = 6.0

    /** 生物回避代价倍增 */
    private const val MOB_AVOIDANCE_PENALTY = 3.0

    // ========== 节点数据类 ==========

    data class PathNode(
        val pos: BlockPos,
        val g: Double,
        val h: Double,
        val parent: PathNode? = null,
        val moveType: MovementType? = null
    ) : Comparable<PathNode> {
        val f: Double get() = g + h
        override fun compareTo(other: PathNode): Int = f.compareTo(other.f)
    }

    // ========== 寻路上下文 ==========

    /**
     * 寻路上下文，包含所有运行时状态。
     * 线程安全：每次寻路创建独立实例。
     */
    class PathContext(
        val level: Level,
        val start: BlockPos,
        val end: BlockPos,
        /** 回退路径集合（已走过的路径，用于回避） */
        val backtrackPaths: Set<Long> = emptySet(),
        /** 是否启用生物回避 */
        val avoidMobs: Boolean = true
    ) {
        val startTime = System.currentTimeMillis()
        val openSet = PriorityQueue<PathNode>()
        val closedSet = HashSet<Long>(1024)
        val gScores = HashMap<Long, Double>(1024)
        val bestPaths = arrayOfNulls<PathNode>(COEFFICIENTS.size)
    }

    // ========== 主入口 ==========

    /**
     * 计算从 start 到 end 的路径。
     */
    fun findPath(
        level: Level,
        start: BlockPos,
        end: BlockPos,
        backtrackPaths: Set<Long> = emptySet(),
        avoidMobs: Boolean = true
    ): List<BlockPos> {
        if (start == end) return listOf(start)
        if (!MovementCostCalculator.canWalkAt(level, end)) return emptyList()

        val ctx = PathContext(level, start, end, backtrackPaths, avoidMobs)
        val startNode = PathNode(start, 0.0, heuristic(start, end))
        ctx.openSet.add(startNode)
        ctx.gScores[posHash(start)] = 0.0

        while (ctx.openSet.isNotEmpty()) {
            // 超时检查
            val elapsed = System.currentTimeMillis() - ctx.startTime
            if (elapsed > PRIMARY_TIMEOUT_MS) {
                val bestPath = getBestPath(ctx)
                if (bestPath != null) return bestPath
                if (elapsed > FAILURE_TIMEOUT_MS) return emptyList()
            }

            val current = ctx.openSet.poll()
            val currentHash = posHash(current.pos)

            if (current.pos == end) {
                return reconstructPath(current)
            }

            if (currentHash in ctx.closedSet) continue
            ctx.closedSet.add(currentHash)

            updateBestPaths(ctx, current)

            // 扩展所有可能的移动
            for (move in MovementType.ALL) {
                val targetPos = getTargetPos(current.pos, move) ?: continue
                val targetHash = posHash(targetPos)

                if (targetHash in ctx.closedSet) continue

                // 区块边界检查
                if (!isChunkLoaded(level, targetPos)) continue

                // 计算移动代价（含回避加成）
                val moveCost = calculateMoveCost(ctx, current.pos, move, targetPos)
                if (moveCost >= ActionCosts.COST_INF) continue

                val tentativeG = current.g + moveCost
                val existingG = ctx.gScores[targetHash]

                if (existingG != null && tentativeG >= existingG) continue

                ctx.gScores[targetHash] = tentativeG
                ctx.openSet.add(PathNode(targetPos, tentativeG, heuristic(targetPos, end), current, move))
            }
        }

        return getBestPath(ctx) ?: emptyList()
    }

    // ========== 代价计算（含回避） ==========

    private fun calculateMoveCost(ctx: PathContext, from: BlockPos, move: MovementType, to: BlockPos): Double {
        var cost = calculateBaseMoveCost(ctx.level, from, move)
        if (cost >= ActionCosts.COST_INF) return cost

        // 回退回避：已走过的路径代价增加
        if (posHash(to) in ctx.backtrackPaths) {
            cost *= BACKTRACK_PENALTY
        }

        // 生物回避：附近有敌对生物时代价增加
        if (ctx.avoidMobs) {
            cost *= getMobAvoidanceMultiplier(ctx.level, to)
        }

        return cost
    }

    private fun calculateBaseMoveCost(level: Level, from: BlockPos, move: MovementType): Double {
        return when (move) {
            MovementType.NORTH, MovementType.SOUTH,
            MovementType.EAST, MovementType.WEST ->
                MovementCostCalculator.horizontalCost(level, from, move, canSprint = true)

            MovementType.NORTH_EAST, MovementType.NORTH_WEST,
            MovementType.SOUTH_EAST, MovementType.SOUTH_WEST ->
                MovementCostCalculator.horizontalCost(level, from, move, canSprint = true)

            MovementType.ASCEND_NORTH, MovementType.ASCEND_SOUTH,
            MovementType.ASCEND_EAST, MovementType.ASCEND_WEST ->
                MovementCostCalculator.ascendCost(level, from, move)

            MovementType.DESCEND_NORTH, MovementType.DESCEND_SOUTH,
            MovementType.DESCEND_EAST, MovementType.DESCEND_WEST ->
                MovementCostCalculator.descendCost(level, from, move)

            MovementType.PILLAR -> MovementCostCalculator.pillarCost(level, from)
            MovementType.DOWNWARD -> MovementCostCalculator.downwardCost(level, from)
        }
    }

    // ========== 回避系统 ==========

    /**
     * 计算生物回避代价倍增系数。
     * 附近的敌对生物越多、越近，代价越高。
     */
    private fun getMobAvoidanceMultiplier(level: Level, pos: BlockPos): Double {
        if (level !is ServerLevel) return 1.0

        val entities = level.getEntities(
            null,
            AABB.ofSize(pos.center, MOB_AVOIDANCE_RADIUS * 2, MOB_AVOIDANCE_RADIUS * 2, MOB_AVOIDANCE_RADIUS * 2)
        ) { it.type.category == MobCategory.MONSTER }

        if (entities.isEmpty()) return 1.0

        var multiplier = 1.0
        for (entity in entities) {
            val distance = entity.position().distanceTo(pos.center)
            if (distance < MOB_AVOIDANCE_RADIUS) {
                // 越近代价越高：1 + penalty * (1 - distance/radius)
                val proximity = 1.0 - distance / MOB_AVOIDANCE_RADIUS
                multiplier += MOB_AVOIDANCE_PENALTY * proximity
            }
        }

        return multiplier
    }

    // ========== 区块边界感知 ==========

    /**
     * 检查目标位置的区块是否已加载。
     */
    private fun isChunkLoaded(level: Level, pos: BlockPos): Boolean {
        return level.hasChunk(pos.x shr 4, pos.z shr 4)
    }

    // ========== 多系数路径跟踪 ==========

    private fun updateBestPaths(ctx: PathContext, node: PathNode) {
        val h = heuristic(node.pos, ctx.end)
        for (i in COEFFICIENTS.indices) {
            val metric = h + node.g / COEFFICIENTS[i]
            val current = ctx.bestPaths[i]
            if (current == null || metric < heuristic(current.pos, ctx.end) + current.g / COEFFICIENTS[i]) {
                ctx.bestPaths[i] = node
            }
        }
    }

    private fun getBestPath(ctx: PathContext): List<BlockPos>? {
        for (node in ctx.bestPaths) {
            if (node != null) {
                val path = reconstructPath(node)
                if (path.size >= MIN_PATH_LENGTH) return path
            }
        }
        return null
    }

    // ========== 工具方法 ==========

    private fun getTargetPos(from: BlockPos, move: MovementType): BlockPos? {
        return from.offset(move.dx, move.dy, move.dz)
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 将 BlockPos 编码为 long。
     * 高 32 位 = x, 中 16 位 = y, 低 32 位 = z
     */
    fun posHash(pos: BlockPos): Long {
        return (pos.x.toLong() shl 32) or (pos.z.toLong() and 0xFFFFFFFFL) or (pos.y.toLong() shl 16)
    }

    private fun reconstructPath(node: PathNode): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var current: PathNode? = node
        while (current != null) {
            path.add(0, current.pos)
            current = current.parent
        }
        return path
    }
}
