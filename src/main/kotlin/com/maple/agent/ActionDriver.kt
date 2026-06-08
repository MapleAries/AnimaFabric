package com.maple.agent

import com.maple.entity.FakePlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

interface ActionDriver {
    suspend fun playerCommand(command: String): Boolean
    suspend fun adminCommand(command: String): Boolean
    suspend fun sendMessage(message: String): Boolean
    suspend fun killBot(): Boolean
}

class CarpetActionDriver(
    private val botName: String,
    private val server: MinecraftServer,
    private val allowAdminTools: Boolean
) : ActionDriver {
    override suspend fun playerCommand(command: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                val fullCommand = "/player $botName $command"
                println("[AnimaFabric] Carpet action: $fullCommand")
                server.commands.performPrefixedCommand(server.createCommandSourceStack(), fullCommand)
                true
            } catch (e: Exception) {
                println("[AnimaFabric] Carpet action failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun adminCommand(command: String): Boolean {
        if (!allowAdminTools) {
            println("[AnimaFabric] Admin action blocked by config: $command")
            return false
        }

        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                println("[AnimaFabric] Admin action: $command")
                server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
                true
            } catch (e: Exception) {
                println("[AnimaFabric] Admin action failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun sendMessage(message: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "/say [$botName] $message"
                )
                true
            } catch (e: Exception) {
                println("[AnimaFabric] Carpet message failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun killBot(): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "/player $botName kill"
                )
                true
            } catch (e: Exception) {
                println("[AnimaFabric] Carpet kill failed: ${e.message}")
                false
            }
        }
    }
}

class NativeActionDriver(
    private val botName: String,
    private val server: MinecraftServer,
    private val allowAdminTools: Boolean
) : ActionDriver {
    override suspend fun playerCommand(command: String): Boolean {
        val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        return when (parts.first().lowercase()) {
            "stop" -> stopBot()
            "look" -> look(parts.drop(1))
            "move" -> move(parts.drop(1))
            "jump" -> jump(parts.drop(1))
            "sneak" -> sneak(true)
            "unsneak" -> sneak(false)
            "sprint" -> sprint(true)
            "unsprint" -> sprint(false)
            "attack" -> attack(parts.drop(1))
            "use" -> use(parts.drop(1))
            else -> {
                println("[AnimaFabric] Native action driver does not support '$command' yet for $botName")
                false
            }
        }
    }

    override suspend fun adminCommand(command: String): Boolean {
        if (!allowAdminTools) {
            println("[AnimaFabric] Admin action blocked by config: $command")
            return false
        }

        return GameThreadDispatcher.runOnGameThread(server) {
            try {
                println("[AnimaFabric] Admin action: $command")
                server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
                true
            } catch (e: Exception) {
                println("[AnimaFabric] Admin action failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun sendMessage(message: String): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            server.playerList.broadcastSystemMessage(
                Component.literal("[${bot.name.string}] $message"),
                false
            )
            true
        }
    }

    override suspend fun killBot(): Boolean {
        cancelContinuousAction(botName)
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            bot.kill(bot.level())
            true
        }
    }

    private suspend fun stopBot(): Boolean {
        cancelContinuousAction(botName)
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            bot.stopUsingItem()
            bot.setSprinting(false)
            bot.setShiftKeyDown(false)
            bot.deltaMovement = Vec3.ZERO
            true
        }
    }

    private suspend fun move(args: List<String>): Boolean {
        val direction = args.firstOrNull()?.lowercase() ?: return false
        if (direction !in setOf("forward", "backward", "left", "right")) return false

        startContinuousAction(botName) {
            while (true) {
                GameThreadDispatcher.runOnGameThread(server) {
                    val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread
                    applyHorizontalVelocity(bot, direction)
                }
                delay(50)
            }
        }
        return true
    }

    private suspend fun jump(args: List<String>): Boolean {
        val mode = args.firstOrNull()?.lowercase()
        return when (mode) {
            "continuous" -> {
                startContinuousAction(botName) {
                    while (true) {
                        jumpOnce()
                        delay(350)
                    }
                }
                true
            }
            "interval" -> {
                val intervalTicks = args.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1) ?: 20L
                startContinuousAction(botName) {
                    while (true) {
                        jumpOnce()
                        delay(intervalTicks * 50L)
                    }
                }
                true
            }
            else -> jumpOnce()
        }
    }

    private suspend fun jumpOnce(): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            if (bot.onGround()) {
                bot.deltaMovement = Vec3(bot.deltaMovement.x, 0.42, bot.deltaMovement.z)
            }
            true
        }
    }

    private suspend fun sneak(enable: Boolean): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            bot.setShiftKeyDown(enable)
            true
        }
    }

    private suspend fun sprint(enable: Boolean): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            bot.setSprinting(enable)
            true
        }
    }

    private suspend fun attack(args: List<String>): Boolean {
        return runActionMode(args, defaultIntervalMs = 250L) {
            attackOnce()
        }
    }

    private suspend fun attackOnce(): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            val entityHit = findLookEntity(bot, 4.5)
            if (entityHit != null) {
                bot.attack(entityHit.entity)
                bot.swing(InteractionHand.MAIN_HAND)
                true
            } else {
                false
            }
        }
    }

    private suspend fun use(args: List<String>): Boolean {
        return runActionMode(args, defaultIntervalMs = 250L) {
            useOnce()
        }
    }

    private suspend fun useOnce(): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            val hand = InteractionHand.MAIN_HAND
            val stack = bot.getItemInHand(hand)
            val hit = bot.pick(4.5, 0.0f, false)

            val entityHit = findLookEntity(bot, 4.5)
            val result = when {
                entityHit != null -> bot.interactOn(entityHit.entity, hand, entityHit.location)
                hit.type == HitResult.Type.BLOCK -> bot.gameMode.useItemOn(bot, bot.level(), stack, hand, hit as BlockHitResult)

                else -> bot.gameMode.useItem(bot, bot.level(), stack, hand)
            }

            val consumed = result.consumesAction()
            if (consumed) {
                bot.swing(hand)
            }
            consumed
        }
    }

    private suspend fun runActionMode(
        args: List<String>,
        defaultIntervalMs: Long,
        action: suspend () -> Boolean
    ): Boolean {
        return when (args.firstOrNull()?.lowercase()) {
            "continuous" -> {
                startContinuousAction(botName) {
                    while (true) {
                        action()
                        delay(defaultIntervalMs)
                    }
                }
                true
            }
            "interval" -> {
                val intervalTicks = args.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1) ?: 20L
                startContinuousAction(botName) {
                    while (true) {
                        action()
                        delay(intervalTicks * 50L)
                    }
                }
                true
            }
            else -> action()
        }
    }

    private suspend fun look(args: List<String>): Boolean {
        if (args.size == 4 && args[0].equals("at", ignoreCase = true)) {
            val x = args[1].toDoubleOrNull() ?: return false
            val y = args[2].toDoubleOrNull() ?: return false
            val z = args[3].toDoubleOrNull() ?: return false
            return lookAt(x, y, z)
        }

        if (args.size >= 2) {
            val yaw = args[0].toFloatOrNull()
            val pitch = args[1].toFloatOrNull()
            if (yaw != null && pitch != null) {
                return setRotation(yaw, pitch)
            }
        }

        if (args.size == 1) {
            val rotation = when (args[0].lowercase()) {
                "south", "s" -> 0.0f to 0.0f
                "west", "w" -> 90.0f to 0.0f
                "north", "n" -> 180.0f to 0.0f
                "east", "e" -> 270.0f to 0.0f
                "up" -> null to -90.0f
                "down" -> null to 90.0f
                else -> return false
            }
            val yaw = rotation.first
            val pitch = rotation.second
            return GameThreadDispatcher.runOnGameThread(server) {
                val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
                setBotRotation(bot, yaw ?: bot.yRot, pitch)
                true
            }
        }

        return false
    }

    private suspend fun lookAt(x: Double, y: Double, z: Double): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            val target = Vec3(x + 0.5, y + 0.5, z + 0.5)
            val delta = target.subtract(bot.eyePosition)
            val horizontal = Mth.sqrt((delta.x * delta.x + delta.z * delta.z).toFloat()).toDouble()
            val yaw = (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG).toFloat() - 90.0f
            val pitch = (-(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG)).toFloat()
            setBotRotation(bot, yaw, pitch)
            true
        }
    }

    private suspend fun setRotation(yaw: Float, pitch: Float): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            setBotRotation(bot, yaw, pitch)
            true
        }
    }

    private fun setBotRotation(bot: ServerPlayer, yaw: Float, pitch: Float) {
        val wrappedYaw = Mth.wrapDegrees(yaw)
        val clampedPitch = Mth.clamp(pitch, -90.0f, 90.0f)
        bot.yRot = wrappedYaw
        bot.xRot = clampedPitch
        bot.yHeadRot = wrappedYaw
        bot.yBodyRot = wrappedYaw
        bot.connection.teleport(bot.x, bot.y, bot.z, wrappedYaw, clampedPitch)
    }

    private fun applyHorizontalVelocity(bot: ServerPlayer, direction: String) {
        val yawRadians = Math.toRadians(bot.yRot.toDouble())
        val forward = Vec3(-Math.sin(yawRadians), 0.0, Math.cos(yawRadians))
        val left = Vec3(Math.cos(yawRadians), 0.0, Math.sin(yawRadians))
        val movement = when (direction) {
            "forward" -> forward
            "backward" -> forward.scale(-1.0)
            "left" -> left
            "right" -> left.scale(-1.0)
            else -> Vec3.ZERO
        }
        val speed = when {
            bot.isShiftKeyDown -> 0.04
            bot.isSprinting -> 0.18
            else -> 0.12
        }
        bot.deltaMovement = Vec3(movement.x * speed, bot.deltaMovement.y, movement.z * speed)
    }

    private fun findLookEntity(bot: ServerPlayer, range: Double): EntityHitResult? {
        val eye = bot.eyePosition
        val look = bot.lookAngle
        val end = eye.add(look.scale(range))
        val searchBox = AABB(eye, end).inflate(1.0)

        return bot.level()
            .getEntities(bot, searchBox) { entity -> entity.isPickable && !entity.isSpectator }
            .mapNotNull { entity ->
                val hit = entity.boundingBox.inflate(entity.pickRadius.toDouble()).clip(eye, end)
                if (hit.isPresent) EntityHitResult(entity, hit.get()) to eye.distanceToSqr(hit.get()) else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    companion object {
        private val actionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val continuousActions = ConcurrentHashMap<String, Job>()

        private fun startContinuousAction(botName: String, block: suspend () -> Unit) {
            cancelContinuousAction(botName)
            continuousActions[botName] = actionScope.launch { block() }
        }

        private fun cancelContinuousAction(botName: String) {
            continuousActions.remove(botName)?.cancel()
        }
    }
}

object ActionDriverFactory {
    fun create(
        name: String,
        botName: String,
        server: MinecraftServer,
        allowAdminTools: Boolean
    ): ActionDriver {
        return when (name.lowercase()) {
            "native" -> NativeActionDriver(botName, server, allowAdminTools)
            "carpet" -> CarpetActionDriver(botName, server, allowAdminTools)
            else -> {
                println("[AnimaFabric] Unknown actionDriver '$name', falling back to carpet")
                CarpetActionDriver(botName, server, allowAdminTools)
            }
        }
    }
}
