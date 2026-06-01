package com.maple.entity

import com.maple.agent.ActionPack
import com.mojang.authlib.GameProfile
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.ai.attributes.Attributes
import java.util.UUID

/**
 * 假玩家实体，参考 carpet 的 EntityPlayerMPFake。
 * 继承 ServerPlayer，使用无操作网络连接。
 */
class FakePlayer(
    level: ServerLevel,
    profile: GameProfile
) : ServerPlayer(level.server, level, profile, ClientInformation.createDefault()) {

    val actionPack = ActionPack()
    var botName: String = profile.name

    init {
        this.health = 20.0f
        getAttribute(Attributes.STEP_HEIGHT)?.baseValue = 0.6
    }

    override fun tick() {
        super.tick()
        actionPack.onUpdate(this)
    }

    override fun die(damageSource: DamageSource) {
        super.die(damageSource)
        this.health = 20.0f
        this.foodData.foodLevel = 20
        this.discard()
    }

    override fun getIpAddress(): String = "127.0.0.1"

    override fun isInvulnerableTo(level: ServerLevel, source: DamageSource): Boolean {
        return super.isInvulnerableTo(level, source)
    }

    companion object {
        /**
         * 创建假玩家。
         */
        fun create(level: ServerLevel, name: String, x: Double, y: Double, z: Double): FakePlayer {
            val uuid = UUID.nameUUIDFromBytes("AnimaFabric:$name".toByteArray())
            val profile = GameProfile(uuid, "[AI] $name")
            val fakePlayer = FakePlayer(level, profile)

            fakePlayer.setPos(x, y, z)

            val server = level.server
            val cookie = CommonListenerCookie.createInitial(profile, false)
            server.playerList.placeNewPlayer(FakeClientConnection(level.server), fakePlayer, cookie)

            return fakePlayer
        }

        /**
         * 移除假玩家。
         */
        fun remove(player: FakePlayer) {
            player.actionPack.stopAll()
            player.discard()
            player.level().server.playerList.remove(player)
        }
    }
}
