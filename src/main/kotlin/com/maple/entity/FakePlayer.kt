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

        // 同步潜行状态到实体（视觉蹲下 + 边缘防掉落）
        this.isShiftKeyDown = actionPack.sneaking
        if (actionPack.sneaking) {
            setPose(net.minecraft.world.entity.Pose.CROUCHING)
        } else {
            setPose(net.minecraft.world.entity.Pose.STANDING)
        }

        // 调试：显示状态
        val isOnGround = onGround()
        if (actionPack.wantsJump || actionPack.sneaking || actionPack.forward != 0f) {
            println("[AnimaFabric] tick: onGround=$isOnGround, wantsJump=${actionPack.wantsJump}, sneak=${actionPack.sneaking}, fwd=${actionPack.forward}, y=$y")
        }

        // 计算水平移动
        var moveX = 0.0
        var moveZ = 0.0
        if (actionPack.forward != 0f || actionPack.strafing != 0f) {
            val vel = if (actionPack.sneaking) 0.03f else 0.1f
            val fwd = actionPack.forward * vel
            val strafe = actionPack.strafing * vel
            val yawRad = Math.toRadians(yRot.toDouble())
            val sin = Math.sin(yawRad).toFloat()
            val cos = Math.cos(yawRad).toFloat()
            moveX = (strafe * cos - fwd * sin).toDouble()
            moveZ = (strafe * sin + fwd * cos).toDouble()
        }

        // 计算垂直移动
        var moveY = 0.0
        if (actionPack.wantsJump && isOnGround) {
            moveY = 0.42
            actionPack.wantsJump = false
            println("[AnimaFabric] JUMPING! moveY=$moveY")
        }

        // 重力
        if (!isOnGround) {
            val motion = deltaMovement
            moveY = (motion.y - 0.08) * 0.98
        }

        // 应用移动（一次 move 调用）
        if (moveX != 0.0 || moveY != 0.0 || moveZ != 0.0) {
            move(net.minecraft.world.entity.MoverType.SELF, net.minecraft.world.phys.Vec3(moveX, moveY, moveZ))
        }
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
            val profile = GameProfile(uuid, "[AI]$name")
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
