package com.maple.entity

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * 管理 Carpet 假人的检测和控制。
 * 不自己生成假人，由用户通过 /player <name> spawn 生成。
 */
object FakePlayerManager {

    /**
     * 获取所有 Carpet 假人（名字包含在 Carpet 玩家列表中的）。
     */
    fun getCarpetBots(server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { player ->
            val name = player.name.string
            // Carpet 假人是普通的 ServerPlayer，通过名字特征识别
            name.startsWith("[") && name.contains("]") ||
            name.startsWith("AI_") ||
            name.contains("AI")
        }
    }

    /**
     * 获取指定名称的假人。
     */
    fun getBot(server: MinecraftServer, name: String): ServerPlayer? {
        // 直接查找
        val direct = server.playerList.getPlayerByName(name)
        if (direct != null) return direct

        // 带前缀查找
        val withPrefix = server.playerList.getPlayerByName("AI_$name")
        if (withPrefix != null) return withPrefix

        // 模糊匹配
        return server.playerList.players.find { player ->
            val playerName = player.name.string
            playerName == name || playerName == "AI_$name" || playerName.contains(name)
        }
    }

    /**
     * 检查假人是否存在。
     */
    fun exists(server: MinecraftServer, name: String): Boolean {
        return getBot(server, name) != null
    }

    /**
     * 获取所有假人名称。
     */
    fun listNames(server: MinecraftServer): List<String> {
        return getCarpetBots(server).map { it.name.string }
    }

    /**
     * 移除指定假人（通过 Carpet 命令）。
     */
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

    /**
     * 移除所有假人。
     */
    fun killAll(server: MinecraftServer) {
        listNames(server).forEach { kill(server, it) }
    }
}
