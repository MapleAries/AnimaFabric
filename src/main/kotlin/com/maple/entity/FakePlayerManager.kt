package com.maple.entity

import carpet.patches.EntityPlayerMPFake
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Manages Carpet fake-player detection and control.
 *
 * This mod does not spawn bots itself; users should create them with
 * `/player <name> spawn`.
 */
object FakePlayerManager {

    fun getCarpetBots(server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filterIsInstance<EntityPlayerMPFake>()
    }

    fun getBot(server: MinecraftServer, name: String): ServerPlayer? {
        return getCarpetBots(server).find { player ->
            val playerName = player.name.string
            playerName == name || playerName == "AI_$name"
        }
    }

    fun exists(server: MinecraftServer, name: String): Boolean {
        return getBot(server, name) != null
    }

    fun listNames(server: MinecraftServer): List<String> {
        return getCarpetBots(server).map { it.name.string }
    }

    fun kill(server: MinecraftServer, name: String): Boolean {
        return try {
            val bot = getBot(server, name) ?: return false
            val botName = bot.name.string
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "/player $botName kill"
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun killAll(server: MinecraftServer) {
        listNames(server).forEach { kill(server, it) }
    }
}
