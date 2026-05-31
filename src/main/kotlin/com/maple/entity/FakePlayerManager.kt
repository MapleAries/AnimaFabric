package com.maple.entity

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * 管理 carpet 假人的检测和控制。
 * 不再自己生成假人，而是检测 carpet 生成的假人。
 */
object FakePlayerManager {

    /**
     * 获取所有 carpet 假人（名字以 [AI] 开头的玩家）。
     */
    fun getCarpetBots(server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { player ->
            player.name.string.startsWith("[") && player.name.string.contains("]")
        }
    }

    /**
     * 获取指定名称的假人。
     * 支持两种格式：完整名称（如 "test"）或带前缀的名称（如 "[AI]test"）。
     */
    fun getBot(server: MinecraftServer, name: String): ServerPlayer? {
        // 先尝试直接查找
        val direct = server.playerList.getPlayerByName(name)
        if (direct != null) return direct

        // 尝试带前缀查找
        val withPrefix = server.playerList.getPlayerByName("[AI]$name")
        if (withPrefix != null) return withPrefix

        // 尝试在所有玩家中模糊匹配
        return server.playerList.players.find { player ->
            val playerName = player.name.string
            playerName.contains(name) || playerName == "[AI]$name"
        }
    }

    /**
     * 检查指定名称的假人是否存在。
     */
    fun exists(server: MinecraftServer, name: String): Boolean {
        return getBot(server, name) != null
    }

    /**
     * 获取所有假人的名称列表。
     */
    fun listNames(server: MinecraftServer): List<String> {
        return getCarpetBots(server).map { it.name.string }
    }

    /**
     * 移除指定名称的假人（通过 carpet 命令）。
     */
    fun kill(server: MinecraftServer, name: String): Boolean {
        return try {
            val bot = getBot(server, name) ?: return false
            val botName = bot.name.string
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "/player $botName kill"
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 移除所有假人。
     */
    fun killAll(server: MinecraftServer) {
        listNames(server).forEach { kill(server, it) }
    }
}
