package com.maple.entity

import net.minecraft.server.MinecraftServer

/**
 * 管理所有 AI bot 的生命周期 - 使用 carpet 的 /player 命令。
 */
object FakePlayerManager {

    private val botNames = mutableSetOf<String>()

    /**
     * 生成一个 AI bot。
     */
    fun spawn(name: String, server: MinecraftServer, x: Double, y: Double, z: Double): Boolean {
        return try {
            val commandManager = server.getCommands()
            val source = server.createCommandSourceStack()

            // 使用 carpet 的 spawn 命令
            commandManager.performPrefixedCommand(source, "/player $name spawn at $x $y $z")
            botNames.add(name)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 移除指定名称的 bot。
     */
    fun kill(name: String, server: MinecraftServer): Boolean {
        return try {
            val commandManager = server.getCommands()
            val source = server.createCommandSourceStack()
            commandManager.performPrefixedCommand(source, "/player $name kill")
            botNames.remove(name)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 移除所有 bot。
     */
    fun killAll(server: MinecraftServer) {
        botNames.toList().forEach { kill(it, server) }
    }

    /**
     * 获取所有 bot 的名称列表。
     */
    fun listNames(): List<String> = botNames.toList()

    /**
     * 检查是否存在指定名称的 bot。
     */
    fun exists(name: String): Boolean = botNames.contains(name)
}
