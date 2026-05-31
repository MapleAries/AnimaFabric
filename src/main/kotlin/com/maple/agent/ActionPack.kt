package com.maple.agent

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import java.util.EnumMap

/**
 * Tick 驱动的动作系统，参考 carpet 的 EntityPlayerActionPack。
 * 管理移动、攻击、使用等动作的状态机。
 */
class ActionPack {

    // 动作类型
    enum class ActionType {
        USE, ATTACK, JUMP, DROP, SWAP_HANDS
    }

    // 动作持续模式
    enum class ActionMode {
        ONCE,       // 单次执行
        CONTINUOUS, // 持续执行（每 tick）
        INTERVAL    // 间隔执行（每 N tick）
    }

    // 动作状态
    data class Action(
        val type: ActionType,
        val mode: ActionMode,
        val limit: Int = 1,         // -1 = 无限
        val interval: Int = 1,
        var count: Int = 0,
        var next: Int = 0,
        var done: Boolean = false
    ) {
        companion object {
            fun once(type: ActionType) = Action(type, ActionMode.ONCE, limit = 1, interval = 1)
            fun continuous(type: ActionType) = Action(type, ActionMode.CONTINUOUS, limit = -1, interval = 1)
            fun interval(type: ActionType, ticks: Int) = Action(type, ActionMode.INTERVAL, limit = -1, interval = ticks)
        }
    }

    private val actions = EnumMap<ActionType, Action>(ActionType::class.java)

    // 移动状态
    var forward = 0f
    var strafing = 0f
    var sneaking = false
    var sprinting = false

    // 方块破坏状态机
    var currentBlock: BlockPos? = null
    var curBlockDamageMP = 0f
    var blockHitDelay = 0
    var isHittingBlock = false

    /**
     * 每 tick 调用，驱动所有活跃动作。
     */
    fun onUpdate(player: ServerPlayer) {
        // 1. 移除已完成的动作
        actions.values.removeAll { it.done }

        // 2. 处理方块破坏延迟
        if (blockHitDelay > 0) {
            blockHitDelay--
        }

        // 3. 执行活跃动作
        val iterator = actions.entries.iterator()
        while (iterator.hasNext()) {
            val (type, action) = iterator.next()
            if (action.done) continue

            action.next--
            if (action.next <= 0) {
                executeAction(player, type)
                action.count++
                action.next = action.interval

                if (action.limit > 0 && action.count >= action.limit) {
                    action.done = true
                    stopAction(type)
                }
            }
        }

        // 4. 应用移动输入
        val vel = if (sneaking) 0.3f else 1.0f
        player.zza = forward * vel
        player.xxa = strafing * vel

        // 5. 设置潜行和冲刺状态
        player.isShiftKeyDown = sneaking
        if (sprinting) {
            player.isSprinting = true
        }
    }

    fun start(type: ActionType, action: Action) {
        actions[type] = action
        action.next = action.interval
    }

    fun stop(type: ActionType) {
        actions.remove(type)
        stopAction(type)
    }

    fun stopAll() {
        actions.keys.toList().forEach { stop(it) }
        forward = 0f
        strafing = 0f
        sneaking = false
        sprinting = false
        currentBlock = null
        curBlockDamageMP = 0f
        blockHitDelay = 0
        isHittingBlock = false
    }

    fun isIdle(): Boolean = actions.isEmpty() && forward == 0f && strafing == 0f

    private fun executeAction(player: ServerPlayer, type: ActionType) {
        when (type) {
            ActionType.ATTACK -> executeAttack(player)
            ActionType.USE -> executeUse(player)
            ActionType.JUMP -> executeJump(player)
            ActionType.DROP -> player.drop(false)
            ActionType.SWAP_HANDS -> swapHands(player)
        }
    }

    private fun executeAttack(player: ServerPlayer) {
        val hitResult = player.pick(5.0, 1.0f, false)
        val level = player.level()

        when (hitResult.type) {
            net.minecraft.world.phys.HitResult.Type.ENTITY -> {
                val entityHit = hitResult as net.minecraft.world.phys.EntityHitResult
                player.attack(entityHit.entity)
            }
            net.minecraft.world.phys.HitResult.Type.BLOCK -> {
                val blockHit = hitResult as net.minecraft.world.phys.BlockHitResult
                val pos = blockHit.blockPos
                val state = level.getBlockState(pos)

                if (state.isAir) return

                // 方块破坏状态机
                if (pos != currentBlock) {
                    currentBlock = pos
                    curBlockDamageMP = 0f
                    isHittingBlock = true
                }

                curBlockDamageMP += state.getDestroyProgress(player, level, pos)

                if (curBlockDamageMP >= 1.0f) {
                    // 破坏方块
                    level.destroyBlock(pos, true, player)
                    curBlockDamageMP = 0f
                    currentBlock = null
                    isHittingBlock = false
                    blockHitDelay = 5
                }
            }
            else -> {}
        }
    }

    private fun executeUse(player: ServerPlayer) {
        val hitResult = player.pick(5.0, 1.0f, false)

        when (hitResult.type) {
            net.minecraft.world.phys.HitResult.Type.ENTITY -> {
                val entityHit = hitResult as net.minecraft.world.phys.EntityHitResult
                player.interactOn(entityHit.entity, InteractionHand.MAIN_HAND)
            }
            net.minecraft.world.phys.HitResult.Type.BLOCK -> {
                val blockHit = hitResult as net.minecraft.world.phys.BlockHitResult
                player.gameMode.useItemOn(player, player.level(), player.mainHandItem, InteractionHand.MAIN_HAND, blockHit)
            }
            else -> {
                player.gameMode.useItem(player, player.level(), player.mainHandItem, InteractionHand.MAIN_HAND)
            }
        }
    }

    private fun executeJump(player: ServerPlayer) {
        if (player.onGround()) {
            player.jumpFromGround()
        }
    }

    private fun swapHands(player: ServerPlayer) {
        val mainHand = player.mainHandItem.copy()
        val offHand = player.offhandItem.copy()
        player.setItemInHand(InteractionHand.MAIN_HAND, offHand)
        player.setItemInHand(InteractionHand.OFF_HAND, mainHand)
    }

    private fun stopAction(type: ActionType) {
        when (type) {
            ActionType.ATTACK -> {
                currentBlock = null
                curBlockDamageMP = 0f
                isHittingBlock = false
            }
            else -> {}
        }
    }
}
