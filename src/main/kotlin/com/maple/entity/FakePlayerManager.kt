package com.maple.entity

import carpet.patches.EntityPlayerMPFake
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Anima-owned bots and Carpet fake-player compatibility.
 */
object FakePlayerManager {
    private val nativeBotIds = ConcurrentHashMap<String, UUID>()

    fun getCarpetBots(server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filterIsInstance<EntityPlayerMPFake>()
    }

    fun spawnNative(server: MinecraftServer, name: String, pos: BlockPos? = null): ServerPlayer? {
        if (getBot(server, name) != null) return null

        val botName = normalizeBotName(name)
        val profile = GameProfile(offlineUuid(botName), botName)
        val level = server.overworld()
        val player = ServerPlayer(server, level, profile, ClientInformation.createDefault())
        val spawnPos = pos ?: BlockPos(0, 80, 0)
        val connection = AnimaFakeConnection()
        val cookie = CommonListenerCookie.createInitial(profile, false)

        player.snapTo(
            spawnPos.x + 0.5,
            spawnPos.y.toDouble(),
            spawnPos.z + 0.5,
            0.0f,
            0.0f
        )
        server.playerList.placeNewPlayer(connection, player, cookie)
        nativeBotIds[botName] = profile.id
        return player
    }

    fun getBot(server: MinecraftServer, name: String): ServerPlayer? {
        val normalized = normalizeBotName(name)
        nativeBotIds[normalized]?.let { id ->
            server.playerList.getPlayer(id)?.let { return it }
        }

        server.playerList.getPlayerByName(normalized)?.let { player ->
            if (nativeBotIds[player.name.string] == player.uuid) return player
        }

        return getCarpetBots(server).find { player ->
            val playerName = player.name.string
            playerName == name || playerName == normalized || playerName == "AI_$name"
        }
    }

    fun exists(server: MinecraftServer, name: String): Boolean {
        return getBot(server, name) != null
    }

    fun listNames(server: MinecraftServer): List<String> {
        val nativeBots = nativeBotIds.keys.mapNotNull { name ->
            server.playerList.getPlayerByName(name)?.name?.string
        }
        return (nativeBots + getCarpetBots(server).map { it.name.string }).distinct()
    }

    fun remove(server: MinecraftServer, name: String): Boolean {
        val bot = getBot(server, name) ?: return false
        nativeBotIds.remove(bot.name.string)
        server.playerList.remove(bot)
        return true
    }

    private fun normalizeBotName(name: String): String {
        return name.take(16)
    }

    private fun offlineUuid(name: String): UUID {
        return UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
    }
}
