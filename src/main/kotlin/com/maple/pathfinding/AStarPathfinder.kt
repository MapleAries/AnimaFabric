package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * 增强版 A* 寻路算法。
 * 支持多种移动类型（水平、对角、上升、下降、掉落、垂直），
 * 使用基于 Minecraft 物理的精确代价模型。
 *
 * 改进点（参考 Baritone）：
 * - 多种移动类型（18 种 vs 原来 4 种）
 * - tick 精确代价模型
 * - 超时机制替代迭代限制
 * - 多系数路径跟踪（返回次优路径）
 */
object AStarPathfinder {

    /** 主搜索超时（毫秒） */
    private const val PRIMARY_TIMEOUT_MS = 3000L

    /** 失败搜索超时（毫秒） */
    private const val FAILURE_TIMEOUT_MS = 5000L

    /** 最短路径长度 */
    private const val MIN_PATH_LENGTH = 3

    /** 多系数跟踪的系数数组 */
    private val COEFFICIENTS = doubleArrayOf(1.5, 2.0, 2.5, 3.0, 4.0, 5.0)

    data class PathNode(
        val pos: BlockPos,
        val g: Double,        // 从起点到当前的实际代价
        val h: Double,        // 启发式估计（到终点的直线距离）
        val parent: PathNode? = null,
        val moveType: MovementType? = null  // 到达此节点的移动类型
    ) : Comparable<PathNode> {
        val f: Double get() = g + h
        override fun compareTo(other: PathNode): Int = f.compareTo(other.f)
    }

    /**
     * 计算从 start 到 end 的路径。
     * 返回路径点列表（包含起点和终点），如果找不到路径返回空列表。
     */
    fun findPath(level: Level, start: BlockPos, end: BlockPos): List<BlockPos> {
        if (start == end) return listOf(start)
        if (!MovementCostCalculator.canWalkAt(level, end)) return emptyList()

        val startTime = System.currentTimeMillis()
        val openSet = PriorityQueue<PathNode>()
        val closedSet = mutableSetOf<Long>() // 使用 long hash 优化
        val gScores = mutableMapOf<Long, Double>()

        // 多系数最佳路径跟踪
        val bestPaths = arrayOfNulls<PathNode>(COEFFICIENTS.size)

        val startNode = PathNode(start, 0.0, heuristic(start, end))
        openSet.add(startNode)
        gScores[posHash(start)] = 0.0

        while (openSet.isNotEmpty()) {
            // 超时检查
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > PRIMARY_TIMEOUT_MS) {
                // 尝试返回已找到的最佳路径
                val bestPath = getBestPath(bestPaths)
                if (bestPath != null) return bestPath
                if (elapsed > FAILURE_TIMEOUT_MS) return emptyList()
            }

            val current = openSet.poll()
            val currentHash = posHash(current.pos)

            if (current.pos == end) {
                return reconstructPath(current)
            }

            if (currentHash in closedSet) continue
            closedSet.add(currentHash)

            // 更新多系数最佳路径
            updateBestPaths(bestPaths, current, end)

            // 扩展所有可能的移动
            for (move in MovementType.ALL) {
                val targetPos = getTargetPos(current.pos, move) ?: continue
                val targetHash = posHash(targetPos)

                if (targetHash in closedSet) continue

                // 计算移动代价
                val moveCost = calculateMoveCost(level, current.pos, move)
                if (moveCost >= ActionCosts.COST_INF) continue

                val tentativeG = current.g + moveCost
                val existingG = gScores[targetHash]

                if (existingG != null && tentativeG >= existingG) continue

                gScores[targetHash] = tentativeG
                val node = PathNode(
                    targetPos,
                    tentativeG,
                    heuristic(targetPos, end),
                    current,
                    move
                )
                openSet.add(node)
            }
        }

        // 开集为空，尝试返回次优路径
        return getBestPath(bestPaths) ?: emptyList()
    }

    /**
     * 计算指定移动的代价。
     */
    private fun calculateMoveCost(level: Level, from: BlockPos, move: MovementType): Double {
        return when (move) {
            // 水平移动
            MovementType.NORTH, MovementType.SOUTH,
            MovementType.EAST, MovementType.WEST ->
                MovementCostCalculator.horizontalCost(level, from, move, canSprint = true)

            // 对角移动
            MovementType.NORTH_EAST, MovementType.NORTH_WEST,
            MovementType.SOUTH_EAST, MovementType.SOUTH_WEST ->
                MovementCostCalculator.horizontalCost(level, from, move, canSprint = true)

            // 上升移动
            MovementType.ASCEND_NORTH, MovementType.ASCEND_SOUTH,
            MovementType.ASCEND_EAST, MovementType.ASCEND_WEST ->
                MovementCostCalculator.ascendCost(level, from, move)

            // 下降移动（动态掉落）
            MovementType.DESCEND_NORTH, MovementType.DESCEND_SOUTH,
            MovementType.DESCEND_EAST, MovementType.DESCEND_WEST ->
                MovementCostCalculator.descendCost(level, from, move)

            // 垂直移动
            MovementType.PILLAR -> MovementCostCalculator.pillarCost(level, from)
            MovementType.DOWNWARD -> MovementCostCalculator.downwardCost(level, from)
        }
    }

    /**
     * 获取移动的目标位置。
     * 对于动态移动（如掉落），返回 null（需要特殊处理）。
     */
    private fun getTargetPos(from: BlockPos, move: MovementType): BlockPos? {
        if (move.dynamicY) {
            // 动态移动，返回名义目标（实际由 cost 函数处理）
            return from.offset(move.dx, move.dy, move.dz)
        }
        return from.offset(move.dx, move.dy, move.dz)
    }

    /**
     * 启发式函数：使用 3D 欧几里得距离。
     */
    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 将 BlockPos 编码为 long 用于高效存储。
     */
    private fun posHash(pos: BlockPos): Long {
        return (pos.x.toLong() shl 32) or (pos.z.toLong() and 0xFFFFFFFFL) or (pos.y.toLong() shl 16)
    }

    /**
     * 更新多系数最佳路径跟踪。
     */
    private fun updateBestPaths(bestPaths: Array<PathNode?>, node: PathNode, end: BlockPos) {
        val h = heuristic(node.pos, end)
        for (i in COEFFICIENTS.indices) {
            val metric = h + node.g / COEFFICIENTS[i]
            val current = bestPaths[i]
            if (current == null || metric < heuristic(current.pos, end) + current.g / COEFFICIENTS[i]) {
                bestPaths[i] = node
            }
        }
    }

    /**
     * 从多系数跟踪中获取最佳路径。
     */
    private fun getBestPath(bestPaths: Array<PathNode?>): List<BlockPos>? {
        for (node in bestPaths) {
            if (node != null) {
                val path = reconstructPath(node)
                if (path.size >= MIN_PATH_LENGTH) return path
            }
        }
        return null
    }

    /**
     * 从节点链重建路径。
     */
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
