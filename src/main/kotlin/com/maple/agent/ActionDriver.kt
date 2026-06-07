package com.maple.agent

import net.minecraft.server.MinecraftServer

interface ActionDriver {
    suspend fun playerCommand(command: String): Boolean
    suspend fun adminCommand(command: String): Boolean
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
}

class NativeActionDriver(
    private val botName: String,
    private val server: MinecraftServer,
    private val allowAdminTools: Boolean
) : ActionDriver {
    override suspend fun playerCommand(command: String): Boolean {
        println("[AnimaFabric] Native action driver is not implemented yet for '$command' on $botName")
        return false
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
