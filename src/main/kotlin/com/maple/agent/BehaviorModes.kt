package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.phys.AABB

/**
 * 自动行为模式 - 参考 Mindcraft 的设计。
 * 直接操作 FakePlayer 的 ActionPack，不依赖外部 mod。
 */
class BehaviorModes(private val botName: String, private val server: net.minecraft.server.MinecraftServer) {

    // 行为模式开关
    var selfPreservation = true  // 自我保护
    var unstuck = true           // 脱困
    var selfDefense = true       // 自卫
    var itemCollecting = true    // 拾取物品

    // 状态追踪
    private var lastPosition: net.minecraft.world.phys.Vec3? = null
    private var stuckTicks = 0
    private var lastHealth = 20.0f

    /**
     * 每 300ms 调用一次，检查并执行自动行为。
     * 返回是否中断了当前动作。
     */
    fun tick(): Boolean {
        val bot = server.playerList.getPlayerByName(botName) ?: return false
        val fakePlayer = FakePlayerManager.getFakePlayer(botName)
        var interrupted = false

        // 自我保护模式
        if (selfPreservation && checkSelfPreservation(bot, fakePlayer)) {
            interrupted = true
        }

        // 脱困模式
        if (unstuck && checkUnstuck(bot, fakePlayer)) {
            interrupted = true
        }

        // 自卫模式
        if (selfDefense && checkSelfDefense(bot, fakePlayer)) {
            interrupted = true
        }

        // 拾取物品模式
        if (itemCollecting && checkItemCollecting(bot, fakePlayer)) {
            interrupted = true
        }

        return interrupted
    }

    /**
     * 自我保护：逃离危险（低血量、溺水、燃烧、岩浆）
     */
    private fun checkSelfPreservation(bot: ServerPlayer, fakePlayer: com.maple.entity.FakePlayer?): Boolean {
        val health = bot.health
        val isBurning = bot.remainingFireTicks > 0
        val isInLava = bot.isInLava
        val isDrowning = bot.airSupply < 100

        if (health < 6 || isBurning || isInLava || isDrowning) {
            println("[AnimaFabric] Behavior: Self-preservation triggered (health=$health, burning=$isBurning, lava=$isInLava, drowning=$isDrowning)")
            if (fakePlayer != null) {
                fakePlayer.actionPack.setMovement(1.0f, 0f)
                fakePlayer.actionPack.start(ActionPack.ActionType.JUMP, ActionPack.Action.once(ActionPack.ActionType.JUMP))
            }
            return true
        }

        return false
    }

    /**
     * 脱困：检测卡住状态
     */
    private fun checkUnstuck(bot: ServerPlayer, fakePlayer: com.maple.entity.FakePlayer?): Boolean {
        val currentPos = bot.position()

        if (lastPosition != null) {
            val distance = currentPos.distanceTo(lastPosition!!)
            if (distance < 0.1) {
                stuckTicks++
                if (stuckTicks > 60) { // 3秒没动
                    println("[AnimaFabric] Behavior: Unstuck triggered (stuck for ${stuckTicks * 50}ms)")
                    if (fakePlayer != null) {
                        fakePlayer.actionPack.start(ActionPack.ActionType.JUMP, ActionPack.Action.once(ActionPack.ActionType.JUMP))
                        fakePlayer.actionPack.setMovement(1.0f, 0f)
                    }
                    stuckTicks = 0
                    return true
                }
            } else {
                stuckTicks = 0
            }
        }

        lastPosition = currentPos
        return false
    }

    /**
     * 自卫：攻击附近的敌对生物
     */
    private fun checkSelfDefense(bot: ServerPlayer, fakePlayer: com.maple.entity.FakePlayer?): Boolean {
        val level = bot.level()
        val hostileEntities = level.getEntities(bot, AABB.ofSize(bot.position(), 8.0, 8.0, 8.0)) { entity ->
            entity.type.category == MobCategory.MONSTER
        }

        if (hostileEntities.isNotEmpty()) {
            val nearest = hostileEntities.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                println("[AnimaFabric] Behavior: Self-defense triggered (attacking ${nearest.name.string})")
                if (fakePlayer != null) {
                    fakePlayer.actionPack.lookAtBlock(fakePlayer, nearest.blockPosition())
                    fakePlayer.actionPack.startContinuous(ActionPack.ActionType.ATTACK)
                }
                return true
            }
        }

        return false
    }

    /**
     * 拾取物品：捡起附近的掉落物
     */
    private fun checkItemCollecting(bot: ServerPlayer, fakePlayer: com.maple.entity.FakePlayer?): Boolean {
        val level = bot.level()
        val items = level.getEntities(bot, AABB.ofSize(bot.position(), 3.0, 3.0, 3.0)) { entity ->
            entity is net.minecraft.world.entity.item.ItemEntity
        }

        if (items.isNotEmpty()) {
            val nearest = items.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                if (fakePlayer != null) {
                    fakePlayer.actionPack.lookAtBlock(fakePlayer, nearest.blockPosition())
                    fakePlayer.actionPack.setMovement(1.0f, 0f)
                }
                return true
            }
        }

        return false
    }
}
