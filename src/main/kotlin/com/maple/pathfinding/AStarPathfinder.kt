package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A* 寻路算法，基于方块网格。
 */
object AStarPathfinder {

    private const val MAX_ITERATIONS = 1000

    data class PathNode(
        val pos: BlockPos,
        val g: Double,  // 从起点到当前的实际代价
        val h: Double,  // 启发式估计（到终点的直线距离）
        val parent: PathNode? = null
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
        if (!isWalkable(level, end)) return emptyList()

        val openSet = PriorityQueue<PathNode>()
        val closedSet = mutableSetOf<BlockPos>()
        val gScores = mutableMapOf<BlockPos, Double>()

        val startNode = PathNode(start, 0.0, heuristic(start, end))
        openSet.add(startNode)
        gScores[start] = 0.0

        var iterations = 0

        while (openSet.isNotEmpty() && iterations < MAX_ITERATIONS) {
            iterations++
            val current = openSet.poll()

            if (current.pos == end) {
                return reconstructPath(current)
            }

            if (current.pos in closedSet) continue
            closedSet.add(current.pos)

            for (neighbor in getNeighbors(level, current.pos)) {
                if (neighbor in closedSet) continue

                val moveCost = getMoveCost(current.pos, neighbor)
                val tentativeG = current.g + moveCost

                val existingG = gScores[neighbor]
                if (existingG != null && tentativeG >= existingG) continue

                gScores[neighbor] = tentativeG
                val node = PathNode(neighbor, tentativeG, heuristic(neighbor, end), current)
                openSet.add(node)
            }
        }

        return emptyList() // 找不到路径
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = abs(a.x - b.x).toDouble()
        val dy = abs(a.y - b.y).toDouble()
        val dz = abs(a.z - b.z).toDouble()
        return dx + dy + dz // 曼哈顿距离
    }

    private fun getNeighbors(level: Level, pos: BlockPos): List<BlockPos> {
        val neighbors = mutableListOf<BlockPos>()

        // 4 个水平方向
        val directions = listOf(
            pos.north(), pos.south(), pos.east(), pos.west()
        )

        for (dir in directions) {
            // 检查是否可以走上去（高度差 0 或 1）
            val ground = findGround(level, dir)
            if (ground != null) {
                neighbors.add(ground)
            }
        }

        return neighbors
    }

    /**
     * 找到从指定位置开始的可行走地面。
     * 检查当前位置和上方一格。
     */
    private fun findGround(level: Level, pos: BlockPos): BlockPos? {
        // 检查 pos 本身是否可行走（脚下是固体，头部是非固体）
        if (isWalkableAt(level, pos)) return pos

        // 检查上方一格（爬坡）
        val up = pos.above()
        if (isWalkableAt(level, up)) return up

        // 检查下方一格（下坡）
        val down = pos.below()
        if (isWalkableAt(level, down)) return down

        return null
    }

    /**
     * 检查指定位置是否可行走：
     * - 脚下（pos-1）是固体方块
     * - 脚部（pos）是非固体方块
     * - 头部（pos+1）是非固体方块
     */
    private fun isWalkableAt(level: Level, pos: BlockPos): Boolean {
        val below = level.getBlockState(pos.below())
        val feet = level.getBlockState(pos)
        val head = level.getBlockState(pos.above())

        return below.isSolidRender &&
               !feet.isSolidRender &&
               !head.isSolidRender &&
               !isDangerous(feet)
    }

    private fun isWalkable(level: Level, pos: BlockPos): Boolean {
        return isWalkableAt(level, pos)
    }

    private fun isDangerous(state: BlockState): Boolean {
        val name = state.block.name.string.lowercase()
        return "lava" in name || "fire" in name || "cactus" in name || "magma" in name
    }

    private fun getMoveCost(from: BlockPos, to: BlockPos): Double {
        val dy = abs(from.y - to.y)
        return if (dy > 0) 1.5 else 1.0 // 上坡/下坡代价更高
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
