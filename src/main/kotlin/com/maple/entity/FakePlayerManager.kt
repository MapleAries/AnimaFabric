package com.maple.entity

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 AI 假人的生命周期。
 * 直接使用 FakePlayer 实体，不依赖外部 mod。
 */
object FakePlayerManager {

    // 内部维护所有 AI 假人
    private val bots = ConcurrentHashMap<String, FakePlayer>()

    /**
     * 生成一个 AI 假人。
     * @param name 假人名称（不含 [AI] 前缀）
     * @param x, y, z 生成坐标
     * @return 创建的 FakePlayer 实例
     */
    fun spawn(server: MinecraftServer, name: String, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): FakePlayer {
        // 如果已存在同名假人，先移除
        if (bots.containsKey(name)) {
            kill(server, name)
        }

        val level = server.overworld()
        val fakePlayer = FakePlayer.create(level, name, x, y, z, yaw, pitch)
        bots[name] = fakePlayer
        return fakePlayer
    }

    /**
     * 获取指定名称的假人。
     * 支持直接名称或带 AI_ 前缀的名称。
     */
    fun getBot(server: MinecraftServer, name: String): ServerPlayer? {
        // 先查内部 map（去掉可能的 AI_ 前缀）
        val cleanName = name.removePrefix("AI_")
        bots[cleanName]?.let { return it }

        // 尝试在所有在线玩家中查找
        return server.playerList.players.find { player ->
            val playerName = player.name.string
            playerName == name || playerName == "AI_$name" || playerName == cleanName
        }
    }

    /**
     * 检查假人是否存在。
     */
    fun exists(server: MinecraftServer, name: String): Boolean {
        return getBot(server, name) != null
    }

    /**
     * 获取所有 AI 假人名称。
     */
    fun listNames(server: MinecraftServer): List<String> {
        return bots.keys.toList()
    }

    /**
     * 移除指定假人。
     */
    fun kill(server: MinecraftServer, name: String): Boolean {
        val cleanName = name.removePrefix("[AI] ").removePrefix("[AI]")
        val fakePlayer = bots.remove(cleanName) ?: return false
        FakePlayer.remove(fakePlayer)
        return true
    }

    /**
     * 移除所有假人。
     */
    fun killAll(server: MinecraftServer) {
        bots.values.forEach { player ->
            FakePlayer.remove(player)
        }
        bots.clear()
    }

    /**
     * 获取内部的 FakePlayer 实例（需要直接操作 ActionPack 时使用）。
     */
    fun getFakePlayer(name: String): FakePlayer? {
        val cleanName = name.removePrefix("AI_")
        return bots[cleanName]
    }
}
