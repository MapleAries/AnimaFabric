package com.maple.agent

import net.minecraft.server.level.ServerPlayer

/**
 * 行为基类 - 每 tick 自动执行的持续行为。
 * 参考 Baritone 的 Behavior 模式。
 *
 * 与 Process 不同，Behavior 是无目标的、持续运行的。
 * 例如：LookBehavior（视角管理）、InventoryBehavior（背包管理）
 */
abstract class Behavior(val name: String) {

    /** 是否启用 */
    var enabled = true

    /**
     * 每 tick 调用。
     * @param player 关联的玩家
     */
    abstract fun tick(player: ServerPlayer)

    /**
     * 当行为被禁用或移除时调用。
     */
    open fun onDisable() {}

    /**
     * 重置行为状态。
     */
    open fun reset() {}
}

/**
 * 行为管理器 - 管理所有活跃的 Behavior。
 */
class BehaviorManager {
    private val behaviors = mutableListOf<Behavior>()

    fun add(behavior: Behavior) {
        behaviors.add(behavior)
    }

    fun remove(name: String) {
        behaviors.find { it.name == name }?.onDisable()
        behaviors.removeAll { it.name == name }
    }

    fun tick(player: ServerPlayer) {
        for (behavior in behaviors) {
            if (behavior.enabled) {
                behavior.tick(player)
            }
        }
    }

    fun reset() {
        behaviors.forEach { it.reset() }
    }

    fun get(name: String): Behavior? = behaviors.find { it.name == name }
}
