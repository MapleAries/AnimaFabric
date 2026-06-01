package com.maple.agent

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.phys.AABB

/**
 * 自动行为模式 - 参考 Mindcraft 的设计。
 * 这些模式独立于 LLM 决策，每 300ms 检查一次。
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
        var interrupted = false

        // 自我保护模式
        if (selfPreservation && checkSelfPreservation(bot)) {
            interrupted = true
        }

        // 脱困模式
        if (unstuck && checkUnstuck(bot)) {
            interrupted = true
        }

        // 自卫模式
        if (selfDefense && checkSelfDefense(bot)) {
            interrupted = true
        }

        // 拾取物品模式
        if (itemCollecting && checkItemCollecting(bot)) {
            interrupted = true
        }

        return interrupted
    }

    /**
     * 自我保护：逃离危险（低血量、溺水、燃烧、岩浆）
     */
    private fun checkSelfPreservation(bot: ServerPlayer): Boolean {
        val health = bot.health
        val isBurning = bot.remainingFireTicks > 0
        val isInLava = bot.isInLava
        val isDrowning = bot.airSupply < 100

        // 低血量或危险状态
        if (health < 6 || isBurning || isInLava || isDrowning) {
            println("[AnimaFabric] Behavior: Self-preservation triggered (health=$health, burning=$isBurning, lava=$isInLava, drowning=$isDrowning)")
            // 尝试逃离
            executeCarpetCommand(bot, "move forward")
            executeCarpetCommand(bot, "jump")
            return true
        }

        return false
    }

    /**
     * 脱困：检测卡住状态
     */
    private fun checkUnstuck(bot: ServerPlayer): Boolean {
        val currentPos = bot.position()

        if (lastPosition != null) {
            val distance = currentPos.distanceTo(lastPosition!!)
            if (distance < 0.1) {
                stuckTicks++
                if (stuckTicks > 60) { // 3秒没动
                    println("[AnimaFabric] Behavior: Unstuck triggered (stuck for ${stuckTicks * 50}ms)")
                    // 尝试跳跃和移动
                    executeCarpetCommand(bot, "jump")
                    executeCarpetCommand(bot, "move forward")
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
    private fun checkSelfDefense(bot: ServerPlayer): Boolean {
        val level = bot.level()
        val hostileEntities = level.getEntities(bot, AABB.ofSize(bot.position(), 8.0, 8.0, 8.0)) { entity ->
            entity.type.category == MobCategory.MONSTER
        }

        if (hostileEntities.isNotEmpty()) {
            val nearest = hostileEntities.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                println("[AnimaFabric] Behavior: Self-defense triggered (attacking ${nearest.name.string})")
                // 看向敌人并攻击
                executeCarpetCommand(bot, "look at ${nearest.blockPosition().x} ${nearest.blockPosition().y} ${nearest.blockPosition().z}")
                executeCarpetCommand(bot, "attack continuous")
                return true
            }
        }

        return false
    }

    /**
     * 拾取物品：捡起附近的掉落物
     */
    private fun checkItemCollecting(bot: ServerPlayer): Boolean {
        val level = bot.level()
        val items = level.getEntities(bot, AABB.ofSize(bot.position(), 3.0, 3.0, 3.0)) { entity ->
            entity is net.minecraft.world.entity.item.ItemEntity
        }

        if (items.isNotEmpty()) {
            // 走向最近的物品
            val nearest = items.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                executeCarpetCommand(bot, "look at ${nearest.blockPosition().x} ${nearest.blockPosition().y} ${nearest.blockPosition().z}")
                executeCarpetCommand(bot, "move forward")
                return true
            }
        }

        return false
    }

    /**
     * 执行 carpet 命令。
     */
    private fun executeCarpetCommand(bot: ServerPlayer, command: String): Boolean {
        return try {
            val fullCommand = "/player $botName $command"
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                fullCommand
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
