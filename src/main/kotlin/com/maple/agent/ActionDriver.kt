package com.maple.agent

import com.maple.entity.FakePlayerManager
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

interface ActionDriver {
    suspend fun playerCommand(command: String): Boolean
    suspend fun adminCommand(command: String): Boolean
    suspend fun sendMessage(message: String): Boolean
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

    private suspend fun stopBot(): Boolean {
        return GameThreadDispatcher.runOnGameThread(server) {
            val bot = FakePlayerManager.getBot(server, botName) ?: return@runOnGameThread false
            bot.stopUsingItem()
            bot.setSprinting(false)
            bot.setShiftKeyDown(false)
            bot.deltaMovement = Vec3.ZERO
            true
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
