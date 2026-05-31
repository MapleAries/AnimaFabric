package com.maple.entity

import com.maple.agent.ActionPack
import com.mojang.authlib.GameProfile
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import java.util.UUID

/**
 * 假玩家实体，参考 carpet 的 EntityPlayerMPFake。
 * 继承 ServerPlayer，使用无操作网络连接。
 */
class FakePlayer(
    level: ServerLevel,
    profile: GameProfile
) : ServerPlayer(level.server, level, profile, level.server.createClientInformation(profile)) {

    val actionPack = ActionPack()
    var botName: String = profile.name

    init {
        // 设置初始位置和状态
        this.health = 20.0f
        this.maxUpStep = 0.6f
    }

    override fun tick() {
        super.tick()
        actionPack.onUpdate(this)
    }

    override fun die(damageSource: DamageSource) {
        super.die(damageSource)
        // 假玩家死亡后自动移除
        this.health = 20.0f
        this.foodData.foodLevel = 20
        this.discard()
    }

    override fun getIpAddress(): String = "127.0.0.1"

    override fun isInvulnerableTo(damageSource: DamageSource): Boolean {
        // 可以根据需要配置是否无敌
        return super.isInvulnerableTo(damageSource)
    }

    companion object {
        /**
         * 创建假玩家。
         */
        fun create(level: ServerLevel, name: String, x: Double, y: Double, z: Double): FakePlayer {
            val uuid = UUID.nameUUIDFromBytes("MC-Mind:$name".toByteArray())
            val profile = GameProfile(uuid, "[AI] $name")
            val fakePlayer = FakePlayer(level, profile)

            // 设置位置
            fakePlayer.moveTo(x, y, z, 0f, 0f)

            // 注册到服务器玩家列表
            val server = level.server
            server.playerList.placeNewPlayer(FakeClientConnection(), fakePlayer)

            return fakePlayer
        }

        /**
         * 移除假玩家。
         */
        fun remove(player: FakePlayer) {
            player.actionPack.stopAll()
            player.discard()
            // 从玩家列表中移除
            player.server.playerList.remove(player)
        }
    }
}
