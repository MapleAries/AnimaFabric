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
 * 假玩家实体。
 * 继承 ServerPlayer，使用无操作网络连接（EmbeddedChannel）。
 * 通过 ActionPack 驱动行为，每 tick 由自身 tick() 方法驱动。
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
        actionPack.onUpdate(this)

        // 跳跃：设置向上速度
        if (actionPack.wantsJump && onGround()) {
            val motion = deltaMovement
            setDeltaMovement(motion.x, 0.42, motion.z)
            actionPack.wantsJump = false
        }

        // 水平移动
        if (actionPack.forward != 0f || actionPack.strafing != 0f) {
            val vel = if (actionPack.sneaking) 0.03f else 0.1f
            val fwd = actionPack.forward * vel
            val strafe = actionPack.strafing * vel
            val yawRad = Math.toRadians(yRot.toDouble())
            val sin = Math.sin(yawRad).toFloat()
            val cos = Math.cos(yawRad).toFloat()
            move(
                net.minecraft.world.entity.MoverType.SELF,
                net.minecraft.world.phys.Vec3(
                    (strafe * cos - fwd * sin).toDouble(),
                    0.0,
                    (strafe * sin + fwd * cos).toDouble()
                )
            )
        }

        // 手动重力（不调用 super.tick()，避免冲突）
        if (!onGround()) {
            val motion = deltaMovement
            setDeltaMovement(motion.x, (motion.y - 0.08) * 0.98, motion.z)
            // 应用下落
            move(net.minecraft.world.entity.MoverType.SELF, deltaMovement)
        }

        // 处理 ActionPack 中的其他动作（攻击、使用等）
        // 注意：onUpdate 已经在顶部调用，这里不需要重复
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
        fun create(level: ServerLevel, name: String, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): FakePlayer {
            val uuid = UUID.nameUUIDFromBytes("AnimaFabric:$name".toByteArray())
            val profile = GameProfile(uuid, "[AI] $name")
            val fakePlayer = FakePlayer(level, profile)

            fakePlayer.setPos(x, y, z)
            fakePlayer.yRot = yaw
            fakePlayer.xRot = pitch

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
