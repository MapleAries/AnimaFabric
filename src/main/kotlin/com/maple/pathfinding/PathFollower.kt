package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 路径跟随器，将 A* 路径转化为实际的玩家移动。
 * 支持多种移动类型（水平、对角、上升、下降、垂直）。
 *
 * 改进点：
 * - 支持对角移动
 * - 支持动态掉落
 * - 更精确的卡住检测
 * - 路径点到达判定优化
 */
class PathFollower {

    private var path: List<BlockPos> = emptyList()
    private var currentIndex = 0
    private var targetPos: BlockPos? = null
    private var stuckTicks = 0
    private var lastPos: Vec3? = null
    private var isComplete = false
    private var isFailed = false

    companion object {
        private const val REACH_THRESHOLD = 1.0 // 到达路径点的距离阈值
        private const val STUCK_TIMEOUT = 60    // 卡住超时（tick）
        private const val DIAGONAL_THRESHOLD = 1.4 // 对角移动的到达阈值
    }

    /**
     * 设置要跟随的路径。
     */
    fun setPath(newPath: List<BlockPos>) {
        path = newPath
        currentIndex = 0
        targetPos = path.firstOrNull()
        stuckTicks = 0
        lastPos = null
        isComplete = false
        isFailed = false
    }

    /**
     * 每 tick 调用，驱动玩家沿路径移动。
     * 返回 true 表示路径跟随仍在进行中。
     */
    fun tick(player: ServerPlayer): Boolean {
        if (isComplete || isFailed) return false
        if (path.isEmpty() || targetPos == null) return false

        val playerPos = player.position()
        val target = targetPos!!

        // 计算到目标的距离
        val dx = target.x + 0.5 - playerPos.x
        val dy = target.y.toDouble() - playerPos.y
        val dz = target.z + 0.5 - playerPos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)

        // 检查是否到达当前路径点
        val threshold = if (isDiagonalMove()) DIAGONAL_THRESHOLD else REACH_THRESHOLD
        if (horizontalDist < threshold && kotlin.math.abs(dy) < 2.0) {
            currentIndex++
            if (currentIndex >= path.size) {
                isComplete = true
                stopMoving(player)
                return false
            }
            targetPos = path[currentIndex]
            stuckTicks = 0
        }

        // 检查是否卡住
        if (lastPos != null) {
            val moved = playerPos.distanceTo(lastPos!!)
            if (moved < 0.05) {
                stuckTicks++
                if (stuckTicks > STUCK_TIMEOUT) {
                    isFailed = true
                    stopMoving(player)
                    return false
                }
            } else {
                stuckTicks = 0
            }
        }
        lastPos = playerPos

        // 计算移动方向
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()

        // 设置视角
        player.yRot = yaw
        player.xRot = 0f

        // 判断是否需要跳跃
        val needsJump = dy > 0.3 && player.onGround()

        // 设置移动输入
        player.zza = 1.0f
        player.xxa = 0f
        player.isSprinting = horizontalDist > 5

        // 跳跃
        if (needsJump) {
            player.jumpFromGround()
        }

        return true
    }

    /**
     * 判断当前移动是否为对角移动。
     */
    private fun isDiagonalMove(): Boolean {
        if (currentIndex + 1 >= path.size) return false
        val current = path[currentIndex]
        val next = path[currentIndex + 1]
        val dx = kotlin.math.abs(next.x - current.x)
        val dz = kotlin.math.abs(next.z - current.z)
        return dx > 0 && dz > 0
    }

    fun isComplete(): Boolean = isComplete
    fun isFailed(): Boolean = isFailed

    fun stop() {
        isComplete = true
    }

    private fun stopMoving(player: ServerPlayer) {
        player.zza = 0f
        player.xxa = 0f
        player.isSprinting = false
    }
}
