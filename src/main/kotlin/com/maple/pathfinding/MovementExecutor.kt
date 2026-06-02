package com.maple.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 移动执行器 - 将路径点转化为精确的玩家输入。
 * 参考 Baritone 的 PathExecutor + Movement 子类。
 *
 * 支持：
 * - 水平/对角移动
 * - 攀爬（梯子/藤蔓）
 * - 搭桥（放置方块）
 * - 路径拼接
 * - 安全检查（卡住检测、路径偏离检测）
 */
class MovementExecutor {

    // ========== 状态 ==========

    private var path: List<BlockPos> = emptyList()
    private var currentIndex = 0
    private var stuckTicks = 0
    private var lastPos: Vec3? = null
    private var isComplete = false
    private var isFailed = false
    private var failReason = ""

    // ========== 配置 ==========

    companion object {
        const val REACH_THRESHOLD = 1.0
        const val STUCK_TIMEOUT = 60        // 3 秒
        const val MAX_OFF_PATH_TICKS = 100  // 5 秒偏离路径
        const val MAX_OFF_PATH_DIST = 3.0   // 最大偏离距离
    }

    private var offPathTicks = 0

    // ========== 公开 API ==========

    /**
     * 设置要执行的路径。
     */
    fun setPath(newPath: List<BlockPos>) {
        path = newPath
        currentIndex = 0
        stuckTicks = 0
        lastPos = null
        isComplete = false
        isFailed = false
        failReason = ""
        offPathTicks = 0
    }

    /**
     * 拼接新路径（Path Splicing）。
     * 将新路径拼接到当前路径的剩余部分。
     */
    fun splicePath(newPath: List<BlockPos>) {
        if (newPath.isEmpty()) return

        // 找到当前路径中离新路径起点最近的点
        val currentRemaining = path.drop(currentIndex)
        if (currentRemaining.isEmpty()) {
            setPath(newPath)
            return
        }

        // 简单拼接：保留当前剩余路径 + 新路径
        val spliced = currentRemaining + newPath.drop(1) // 去掉新路径的起点（与当前终点重复）
        path = spliced
        currentIndex = 0
    }

    /**
     * 每 tick 调用，驱动玩家沿路径移动。
     * @return true 表示仍在执行中
     */
    fun tick(player: ServerPlayer): Boolean {
        if (isComplete || isFailed) return false
        if (path.isEmpty()) return false

        val target = path.getOrNull(currentIndex) ?: run {
            isComplete = true
            stopMoving(player)
            return false
        }

        // 安全检查：路径偏离检测
        if (!checkPathProximity(player, target)) return false

        // 安全检查：卡住检测
        if (!checkStuck(player)) return false

        // 计算到目标的距离
        val playerPos = player.position()
        val dx = target.x + 0.5 - playerPos.x
        val dy = target.y.toDouble() - playerPos.y
        val dz = target.z + 0.5 - playerPos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // 到达当前路径点
        val threshold = if (isDiagonalMove()) REACH_THRESHOLD * 1.4 else REACH_THRESHOLD
        if (horizontalDist < threshold && kotlin.math.abs(dy) < 2.0) {
            currentIndex++
            if (currentIndex >= path.size) {
                isComplete = true
                stopMoving(player)
                return false
            }
            stuckTicks = 0
        }

        // 执行移动
        val currentTarget = path.getOrNull(currentIndex) ?: return false
        executeMovement(player, currentTarget)

        return true
    }

    fun isComplete(): Boolean = isComplete
    fun isFailed(): Boolean = isFailed
    fun getFailReason(): String = failReason

    fun stop() {
        isComplete = true
    }

    // ========== 移动执行 ==========

    /**
     * 执行向目标位置的移动。
     */
    private fun executeMovement(player: ServerPlayer, target: BlockPos) {
        val playerPos = player.position()
        val dx = target.x + 0.5 - playerPos.x
        val dy = target.y.toDouble() - playerPos.y
        val dz = target.z + 0.5 - playerPos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // 设置视角
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        player.yRot = yaw
        player.xRot = 0f

        // 检查是否需要攀爬
        if (needsClimbing(player, target)) {
            executeClimbing(player, target)
            return
        }

        // 检查是否需要搭桥
        if (needsBridging(player, target)) {
            executeBridging(player, target)
            return
        }

        // 普通移动
        player.zza = 1.0f
        player.xxa = 0f
        player.isSprinting = horizontalDist > 5

        // 跳跃（高度差）
        if (dy > 0.3 && player.onGround()) {
            player.jumpFromGround()
        }
    }

    // ========== 攀爬 ==========

    /**
     * 检查是否需要攀爬（目标在上方且有梯子/藤蔓）。
     */
    private fun needsClimbing(player: ServerPlayer, target: BlockPos): Boolean {
        val dy = target.y - player.y.toInt()
        if (dy <= 0) return false

        val level = player.level()
        val currentBlock = level.getBlockState(player.blockPosition())
        return BlockClassifier.isClimbable(currentBlock)
    }

    /**
     * 执行攀爬移动。
     * 在梯子/藤蔓上时，设置向上移动输入。
     */
    private fun executeClimbing(player: ServerPlayer, target: BlockPos) {
        val dy = target.y - player.y.toInt()
        if (dy > 0) {
            // 向上攀爬：设置前进 + 跳跃输入
            player.zza = 0f
            player.xxa = 0f
            // 模拟跳跃键使角色沿梯子上升
            if (player.onGround() || isOnLadder(player)) {
                player.jumpFromGround()
            }
        }
    }

    /**
     * 检查是否在梯子/藤蔓上。
     */
    private fun isOnLadder(player: ServerPlayer): Boolean {
        val level = player.level()
        val state = level.getBlockState(player.blockPosition())
        return BlockClassifier.isClimbable(state)
    }

    // ========== 搭桥 ==========

    /**
     * 检查是否需要搭桥（目标在空隙中，脚下无方块）。
     */
    private fun needsBridging(player: ServerPlayer, target: BlockPos): Boolean {
        val level = player.level()
        val belowTarget = target.below()
        val blockBelow = level.getBlockState(belowTarget)

        // 目标脚下是空气，需要搭桥
        return blockBelow.isAir && player.onGround()
    }

    /**
     * 执行搭桥移动。
     * 潜行到边缘 → 看向下方 → 放置方块 → 继续移动
     */
    private fun executeBridging(player: ServerPlayer, target: BlockPos) {
        val level = player.level()

        // 潜行防止掉落
        player.isShiftKeyDown = true

        // 看向目标下方的方块位置（用于放置）
        val belowTarget = target.below()
        val lookX = belowTarget.x + 0.5
        val lookY = belowTarget.y + 0.5
        val lookZ = belowTarget.z + 0.5

        val eyePos = player.eyePosition
        val dx = lookX - eyePos.x
        val dy = lookY - eyePos.y
        val dz = lookZ - eyePos.z
        val xzDist = sqrt(dx * dx + dz * dz)

        player.yRot = Math.toDegrees(atan2(-dx, dz)).toFloat()
        player.xRot = Math.toDegrees(atan2(-dy, xzDist)).toFloat()

        // 放置方块（使用主手物品）
        player.gameMode.useItemOn(
            player,
            level,
            player.mainHandItem,
            InteractionHand.MAIN_HAND,
            net.minecraft.world.phys.BlockHitResult(
                Vec3(lookX, lookY, lookZ),
                net.minecraft.core.Direction.UP,
                belowTarget,
                false
            )
        )

        // 前进
        player.zza = 0.5f
        player.isSprinting = false
    }

    // ========== 安全检查 ==========

    /**
     * 检查玩家是否偏离路径。
     */
    private fun checkPathProximity(player: ServerPlayer, target: BlockPos): Boolean {
        val playerPos = player.position()
        val targetCenter = target.center
        val distance = playerPos.distanceTo(targetCenter)

        if (distance > MAX_OFF_PATH_DIST) {
            offPathTicks++
            if (offPathTicks > MAX_OFF_PATH_TICKS) {
                isFailed = true
                failReason = "路径偏离超过 ${MAX_OFF_PATH_DIST} 格持续 ${MAX_OFF_PATH_TICKS} tick"
                stopMoving(player)
                return false
            }
        } else {
            offPathTicks = 0
        }
        return true
    }

    /**
     * 检查是否卡住。
     */
    private fun checkStuck(player: ServerPlayer): Boolean {
        val currentPos = player.position()

        if (lastPos != null) {
            val moved = currentPos.distanceTo(lastPos!!)
            if (moved < 0.05) {
                stuckTicks++
                if (stuckTicks > STUCK_TIMEOUT) {
                    isFailed = true
                    failReason = "卡住超过 ${STUCK_TIMEOUT * 50}ms"
                    stopMoving(player)
                    return false
                }
            } else {
                stuckTicks = 0
            }
        }

        lastPos = currentPos
        return true
    }

    // ========== 辅助 ==========

    private fun isDiagonalMove(): Boolean {
        val current = path.getOrNull(currentIndex) ?: return false
        val prev = path.getOrNull(currentIndex - 1) ?: return false
        val dx = kotlin.math.abs(current.x - prev.x)
        val dz = kotlin.math.abs(current.z - prev.z)
        return dx > 0 && dz > 0
    }

    private fun stopMoving(player: ServerPlayer) {
        player.zza = 0f
        player.xxa = 0f
        player.isSprinting = false
        player.isShiftKeyDown = false
    }
}
