package com.maple.entity

import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.server.MinecraftServer

/**
 * 假玩家的网络连接。
 * 只广播客户端需要的包（玩家信息、实体生成、元数据），不转发移动确认等服务端包。
 */
class FakeClientConnection(private val server: MinecraftServer) : Connection(PacketFlow.CLIENTBOUND) {

    init {
        // 设置 EmbeddedChannel 使 channel 不为 null
        try {
            val field = Connection::class.java.getDeclaredField("channel")
            field.isAccessible = true
            field.set(this, EmbeddedChannel())
        } catch (_: Exception) {}
    }

    override fun getReceiving(): PacketFlow = PacketFlow.SERVERBOUND

    override fun send(packet: Packet<*>) {
        handlePacket(packet)
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) {
        handlePacket(packet)
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        handlePacket(packet)
    }

    override fun isConnected(): Boolean = true

    override fun <T : PacketListener> setupInboundProtocol(
        protocol: net.minecraft.network.ProtocolInfo<T>,
        packetListener: T
    ) {
        try {
            val field = Connection::class.java.getDeclaredField("packetListener")
            field.isAccessible = true
            field.set(this, packetListener)
        } catch (_: Exception) {}
    }

    override fun setupOutboundProtocol(protocol: net.minecraft.network.ProtocolInfo<*>) {}

    /**
     * 只广播客户端需要识别假玩家的包。
     */
    private fun handlePacket(packet: Packet<*>) {
        when (packet) {
            // 玩家信息更新（Tab 列表）- 客户端需要这个来创建玩家实体
            is ClientboundPlayerInfoUpdatePacket -> broadcastToAll(packet)
            // 实体生成包
            is ClientboundAddEntityPacket -> broadcastToAll(packet)
            // 实体元数据（血量、名称等）
            is ClientboundSetEntityDataPacket -> broadcastToAll(packet)
            // 头部旋转
            is ClientboundRotateHeadPacket -> broadcastToAll(packet)
            // 其他包不广播（移动确认、物品数据等只对假玩家有意义）
        }
    }

    private fun broadcastToAll(packet: Packet<*>) {
        try {
            server.execute {
                for (player in server.playerList.players) {
                    if (player !is FakePlayer) {
                        player.connection.send(packet)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
