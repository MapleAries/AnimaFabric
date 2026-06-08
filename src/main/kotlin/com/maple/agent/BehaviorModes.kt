package com.maple.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.phys.AABB

/**
 * 自动行为模式 - 通过 ActionDriver 控制。
 */
class BehaviorModes(
    private val botName: String,
    private val server: net.minecraft.server.MinecraftServer,
    private val driver: ActionDriver = CarpetActionDriver(botName, server, allowAdminTools = true)
) {

    var selfPreservation = true
    var unstuck = true
    var selfDefense = true
    var itemCollecting = true

    private var lastPosition: net.minecraft.world.phys.Vec3? = null
    private var stuckTicks = 0
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun executePlayerCommand(command: String): Boolean {
        scope.launch {
            driver.playerCommand(command)
        }
        return true
    }

    fun tick(): Boolean {
        val bot = server.playerList.getPlayerByName(botName) ?: return false
        var interrupted = false

        if (selfPreservation && checkSelfPreservation(bot)) interrupted = true
        if (unstuck && checkUnstuck(bot)) interrupted = true
        if (selfDefense && checkSelfDefense(bot)) interrupted = true
        if (itemCollecting && checkItemCollecting(bot)) interrupted = true

        return interrupted
    }

    private fun checkSelfPreservation(bot: ServerPlayer): Boolean {
        val health = bot.health
        val isBurning = bot.remainingFireTicks > 0
        val isInLava = bot.isInLava
        val isDrowning = bot.airSupply < 100

        if (health < 6 || isBurning || isInLava || isDrowning) {
            executePlayerCommand("move forward")
            executePlayerCommand("jump")
            return true
        }
        return false
    }

    private fun checkUnstuck(bot: ServerPlayer): Boolean {
        val currentPos = bot.position()

        if (lastPosition != null) {
            val distance = currentPos.distanceTo(lastPosition!!)
            if (distance < 0.1) {
                stuckTicks++
                if (stuckTicks > 60) {
                    executePlayerCommand("jump")
                    executePlayerCommand("move forward")
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

    private fun checkSelfDefense(bot: ServerPlayer): Boolean {
        val level = bot.level()
        val hostileEntities = level.getEntities(bot, AABB.ofSize(bot.position(), 8.0, 8.0, 8.0)) { entity ->
            entity.type.category == MobCategory.MONSTER
        }

        if (hostileEntities.isNotEmpty()) {
            val nearest = hostileEntities.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                executePlayerCommand("look at ${nearest.blockPosition().x} ${nearest.blockPosition().y} ${nearest.blockPosition().z}")
                executePlayerCommand("attack continuous")
                return true
            }
        }
        return false
    }

    private fun checkItemCollecting(bot: ServerPlayer): Boolean {
        val level = bot.level()
        val items = level.getEntities(bot, AABB.ofSize(bot.position(), 3.0, 3.0, 3.0)) { entity ->
            entity is net.minecraft.world.entity.item.ItemEntity
        }

        if (items.isNotEmpty()) {
            val nearest = items.minByOrNull { it.distanceTo(bot) }
            if (nearest != null) {
                executePlayerCommand("look at ${nearest.blockPosition().x} ${nearest.blockPosition().y} ${nearest.blockPosition().z}")
                executePlayerCommand("move forward")
                return true
            }
        }
        return false
    }
}
