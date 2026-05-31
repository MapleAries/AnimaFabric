package com.maple.entity

import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理所有 AI 假玩家的生命周期。
 */
object FakePlayerManager {

    private val bots = ConcurrentHashMap<String, FakePlayer>()

    /**
     * 生成一个 AI bot。
     */
    fun spawn(name: String, level: ServerLevel, x: Double, y: Double, z: Double): FakePlayer {
        // 如果已存在同名 bot，先移除
        if (bots.containsKey(name)) {
            kill(name)
        }

        val bot = FakePlayer.create(level, name, x, y, z)
        bots[name] = bot
        return bot
    }

    /**
     * 获取指定名称的 bot。
     */
    fun get(name: String): FakePlayer? = bots[name]

    /**
     * 移除指定名称的 bot。
     */
    fun kill(name: String) {
        bots.remove(name)?.let { bot ->
            FakePlayer.remove(bot)
        }
    }

    /**
     * 移除所有 bot。
     */
    fun killAll() {
        bots.keys.toList().forEach { kill(it) }
    }

    /**
     * 获取所有 bot 的名称列表。
     */
    fun listNames(): List<String> = bots.keys.toList()

    /**
     * 检查是否存在指定名称的 bot。
     */
    fun exists(name: String): Boolean = bots.containsKey(name)
}
