package com.maple.mission

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 可组合的奖励系统。
 * 参考 Malmo 的 RewardHandler 模式。
 * 支持多种奖励类型和密度，可叠加组合。
 */
class RewardSystem {

    /** 奖励处理器列表 */
    private val handlers = mutableListOf<RewardHandler>()

    /** 累计总奖励 */
    var totalReward = 0.0
        private set

    /** 奖励历史记录 */
    private val history = ConcurrentLinkedQueue<RewardEvent>()

    /**
     * 添加奖励处理器。
     */
    fun addHandler(handler: RewardHandler) {
        handlers.add(handler)
    }

    /**
     * 移除奖励处理器。
     */
    fun removeHandler(name: String) {
        handlers.removeAll { it.name == name }
    }

    /**
     * 每 tick 调用，检查并触发奖励。
     */
    fun tick(player: ServerPlayer) {
        for (handler in handlers) {
            if (!handler.enabled) continue

            val reward = handler.check(player)
            if (reward != 0.0) {
                totalReward += reward
                history.add(RewardEvent(
                    handlerName = handler.name,
                    value = reward,
                    total = totalReward,
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
    }

    /**
     * 手动触发奖励。
     */
    fun grantReward(name: String, value: Double) {
        totalReward += value
        history.add(RewardEvent(name, value, totalReward, System.currentTimeMillis()))
    }

    /**
     * 重置奖励系统。
     */
    fun reset() {
        totalReward = 0.0
        handlers.forEach { it.reset() }
        history.clear()
    }

    /**
     * 获取奖励历史。
     */
    fun getHistory(): List<RewardEvent> = history.toList()

    /**
     * 获取最近 N 条奖励记录。
     */
    fun getRecentHistory(n: Int): List<RewardEvent> = history.toList().takeLast(n)
}

/**
 * 奖励处理器基类。
 */
abstract class RewardHandler(
    val name: String,
    val value: Double = 1.0,
    val density: RewardDensity = RewardDensity.ONCE
) {
    var enabled = true
    private var triggered = false

    /**
     * 检查是否应该触发奖励。
     * @return 奖励值，0 表示不触发
     */
    fun check(player: ServerPlayer): Double {
        return when (density) {
            RewardDensity.ONCE -> {
                if (!triggered && shouldTrigger(player)) {
                    triggered = true
                    value
                } else 0.0
            }
            RewardDensity.PER_TICK -> {
                if (shouldTrigger(player)) value else 0.0
            }
            RewardDensity.PER_ACTION -> {
                // 由外部调用 checkAction
                0.0
            }
        }
    }

    /**
     * 检查行动触发的奖励。
     */
    fun checkAction(action: String): Double {
        if (density != RewardDensity.PER_ACTION) return 0.0
        return if (shouldTriggerAction(action)) value else 0.0
    }

    protected abstract fun shouldTrigger(player: ServerPlayer): Boolean
    protected open fun shouldTriggerAction(action: String): Boolean = false

    open fun reset() {
        triggered = false
    }
}

/**
 * 奖励事件记录。
 */
data class RewardEvent(
    val handlerName: String,
    val value: Double,
    val total: Double,
    val timestamp: Long
)

// ========== 预定义奖励处理器 ==========

/** 触碰方块奖励 */
class TouchBlockReward(
    name: String,
    private val blockType: String,
    value: Double
) : RewardHandler(name, value, RewardDensity.ONCE) {

    override fun shouldTrigger(player: ServerPlayer): Boolean {
        val lookResult = player.pick(5.0, 1.0f, false)
        if (lookResult.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            val blockHit = lookResult as net.minecraft.world.phys.BlockHitResult
            val state = player.level().getBlockState(blockHit.blockPos)
            return state.block.name.string.lowercase().contains(blockType.lowercase())
        }
        return false
    }
}

/** 收集物品奖励 */
class CollectItemReward(
    name: String,
    private val itemType: String,
    value: Double
) : RewardHandler(name, value, RewardDensity.PER_ACTION) {

    private var lastCount = 0

    override fun shouldTrigger(player: ServerPlayer): Boolean = false

    override fun shouldTriggerAction(action: String): Boolean {
        // 由外部在收集物品时调用
        return action == "collect_item"
    }

    fun checkInventory(player: ServerPlayer): Double {
        var currentCount = 0
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (stack.hoverName.string.lowercase().contains(itemType.lowercase())) {
                currentCount += stack.count
            }
        }
        val gained = currentCount - lastCount
        lastCount = currentCount
        return if (gained > 0) gained * value else 0.0
    }
}

/** 到达位置奖励 */
class ReachPositionReward(
    name: String,
    private val targetPos: BlockPos,
    private val tolerance: Double,
    value: Double
) : RewardHandler(name, value, RewardDensity.ONCE) {

    override fun shouldTrigger(player: ServerPlayer): Boolean {
        return player.position().distanceTo(targetPos.center) < tolerance
    }
}

/** 时间惩罚奖励 */
class TimePenaltyReward(
    name: String,
    value: Double // 应为负数
) : RewardHandler(name, value, RewardDensity.PER_TICK) {

    override fun shouldTrigger(player: ServerPlayer): Boolean = true
}
