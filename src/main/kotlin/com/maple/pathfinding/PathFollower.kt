package com.maple.pathfinding

import com.maple.entity.FakePlayer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 路径跟随器 - 将 A* 路径转化为 ActionPack 移动指令。
 *
 * 改进点：
 * - 使用 ActionPack（setMovement/wantsJump）而非直接设置 player.zza
 * - 对角移动支持（strafing + forward）
 * - 卡住恢复（跳跃 + 换方向）
 * - 冲刺管理
 * - 动态重寻路
 */
class PathFollower {

    private var path: List<BlockPos> = emptyList()
    private var currentIndex = 0
    private var stuckTicks = 0
    private var lastPos: Vec3? = null
    private var isComplete = false
    private var isFailed = false
    private var failReason = ""

    // 卡住恢复状态
    private var recoveryTicks = 0
    private var recoveryPhase = RecoveryPhase.NONE

    companion object {
        private const val REACH_THRESHOLD = 1.2
        private const val DIAGONAL_THRESHOLD = 1.6
        private const val STUCK_TIMEOUT = 40        // 2 秒
        private const val RECOVERY_JUMP_TICKS = 10  // 跳跃恢复持续 tick
        private const val RECOVERY_TURN_TICKS = 15  // 转向恢复持续 tick
        private const val MAX_RECOVERY_ATTEMPTS = 3
    }

    private var recoveryAttempts = 0

    enum class RecoveryPhase {
        NONE, JUMP, TURN_LEFT, TURN_RIGHT
    }

    /**
     * 设置要跟随的路径。
     */
    fun setPath(newPath: List<BlockPos>) {
        path = newPath
        currentIndex = 0
        stuckTicks = 0
        lastPos = null
        isComplete = false
        isFailed = false
        failReason = ""
        recoveryPhase = RecoveryPhase.NONE
        recoveryAttempts = 0
    }

    /**
     * 每 tick 调用，驱动玩家沿路径移动。
     * @param player FakePlayer 实例
     * @return true 表示仍在执行中
     */
    fun tick(player: FakePlayer): Boolean {
        if (isComplete || isFailed) return false
        if (path.isEmpty()) return false

        val target = path.getOrNull(currentIndex) ?: run {
            isComplete = true
            player.actionPack.stopMovement()
            return false
        }

        val playerPos = player.position()
        val dx = target.x + 0.5 - playerPos.x
        val dy = target.y.toDouble() - playerPos.y
        val dz = target.z + 0.5 - playerPos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // 到达当前路径点
        val threshold = if (isDiagonalMove()) DIAGONAL_THRESHOLD else REACH_THRESHOLD
        if (horizontalDist < threshold && abs(dy) < 2.5) {
            currentIndex++
            if (currentIndex >= path.size) {
                isComplete = true
                player.actionPack.stopMovement()
                return false
            }
            stuckTicks = 0
            recoveryPhase = RecoveryPhase.NONE
        }

        // 卡住检测
        if (lastPos != null) {
            val moved = playerPos.distanceTo(lastPos!!)
            if (moved < 0.05) {
                stuckTicks++
                if (stuckTicks > STUCK_TIMEOUT) {
                    // 尝试恢复
                    if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
                        recoveryAttempts++
                        stuckTicks = 0
                        recoveryPhase = when (recoveryPhase) {
                            RecoveryPhase.NONE -> RecoveryPhase.JUMP
                            RecoveryPhase.JUMP -> RecoveryPhase.TURN_LEFT
                            RecoveryPhase.TURN_LEFT -> RecoveryPhase.TURN_RIGHT
                            RecoveryPhase.TURN_RIGHT -> RecoveryPhase.JUMP
                        }
                        recoveryTicks = 0
                    } else {
                        isFailed = true
                        failReason = "卡住超过最大恢复次数"
                        player.actionPack.stopMovement()
                        return false
                    }
                }
            } else {
                stuckTicks = 0
                recoveryAttempts = 0
                recoveryPhase = RecoveryPhase.NONE
            }
        }
        lastPos = playerPos

        // 执行恢复动作
        if (recoveryPhase != RecoveryPhase.NONE) {
            executeRecovery(player)
            return true
        }

        // 正常移动
        executeMovement(player, dx, dy, dz, horizontalDist)
        return true
    }

    /**
     * 执行正常移动。
     */
    private fun executeMovement(player: FakePlayer, dx: Double, dy: Double, dz: Double, horizontalDist: Double) {
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        player.yRot = yaw
        player.xRot = 0f

        // 判断是否对角移动
        val isDiag = isDiagonalMove()

        // 计算前进和横移
        val forward: Float
        val strafe: Float
        if (isDiag) {
            // 对角移动：计算相对于朝向的横移分量
            val yawRad = Math.toRadians(yaw.toDouble())
            val sin = Math.sin(yawRad).toFloat()
            val cos = Math.cos(yawRad).toFloat()
            // 将世界坐标 dx/dz 转换为相对于朝向的前进/横移
            val relForward = (dx * sin + dz * cos).toFloat()
            val relStrafe = (dx * cos - dz * sin).toFloat()
            // 归一化
            val max = maxOf(abs(relForward), abs(relStrafe), 0.01f)
            forward = relForward / max
            strafe = relStrafe / max
        } else {
            forward = 1.0f
            strafe = 0f
        }

        // 冲刺：长距离直线段
        val shouldSprint = horizontalDist > 5 && !isDiag && dy < 0.5

        player.actionPack.setMovement(forward, strafe, shouldSprint)

        // 跳跃：高度差
        if (dy > 0.4 && player.onGround()) {
            player.actionPack.wantsJump = true
        }
    }

    /**
     * 执行卡住恢复动作。
     */
    private fun executeRecovery(player: FakePlayer) {
        recoveryTicks++

        when (recoveryPhase) {
            RecoveryPhase.JUMP -> {
                player.actionPack.setMovement(1.0f, 0f)
                player.actionPack.wantsJump = true
                if (recoveryTicks > RECOVERY_JUMP_TICKS) {
                    recoveryPhase = RecoveryPhase.NONE
                }
            }
            RecoveryPhase.TURN_LEFT -> {
                player.actionPack.setMovement(0f, -1.0f) // 左移
                player.actionPack.wantsJump = true
                if (recoveryTicks > RECOVERY_TURN_TICKS) {
                    recoveryPhase = RecoveryPhase.NONE
                }
            }
            RecoveryPhase.TURN_RIGHT -> {
                player.actionPack.setMovement(0f, 1.0f) // 右移
                player.actionPack.wantsJump = true
                if (recoveryTicks > RECOVERY_TURN_TICKS) {
                    recoveryPhase = RecoveryPhase.NONE
                }
            }
            RecoveryPhase.NONE -> {}
        }
    }

    /**
     * 判断当前移动是否为对角移动。
     */
    private fun isDiagonalMove(): Boolean {
        val current = path.getOrNull(currentIndex) ?: return false
        val prev = path.getOrNull(currentIndex - 1) ?: return false
        val dx = abs(current.x - prev.x)
        val dz = abs(current.z - prev.z)
        return dx > 0 && dz > 0
    }

    fun isComplete(): Boolean = isComplete
    fun isFailed(): Boolean = isFailed
    fun getFailReason(): String = failReason
    fun getCurrentIndex(): Int = currentIndex
    fun getPathLength(): Int = path.size

    fun stop() {
        isComplete = true
    }
}
