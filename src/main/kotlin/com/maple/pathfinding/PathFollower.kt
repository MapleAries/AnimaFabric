package com.maple.pathfinding

import com.maple.agent.ActionPack
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 路径跟随器，将 A* 路径转化为 ActionPack 的移动指令。
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
        private const val REACH_THRESHOLD = 0.8 // 到达路径点的距离阈值
        private const val STUCK_TIMEOUT = 40    // 卡住超时（tick）
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

        // 检查是否到达当前路径点
        val distance = sqrt(
            (playerPos.x - target.x - 0.5) * (playerPos.x - target.x - 0.5) +
            (playerPos.z - target.z - 0.5) * (playerPos.z - target.z - 0.5)
        )

        if (distance < REACH_THRESHOLD) {
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
        val dx = target.x + 0.5 - playerPos.x
        val dz = target.z + 0.5 - playerPos.z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()

        // 设置视角
        player.yRot = yaw
        player.xRot = 0f

        // 检查是否需要跳跃（高度差）
        val dy = target.y - playerPos.y.toInt()
        if (dy > 0 && player.onGround()) {
            player.jumpFromGround()
        }

        // 设置移动输入
        player.zza = 1.0f
        player.xxa = 0f
        player.isSprinting = distance > 5

        return true
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
