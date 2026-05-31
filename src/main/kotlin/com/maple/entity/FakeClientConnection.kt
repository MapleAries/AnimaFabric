package com.maple.entity

import io.netty.channel.ChannelFutureListener
import io.netty.channel.DefaultEventLoop
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer

/**
 * 假玩家的网络连接。
 * 使用 EmbeddedChannel 使 isOpen() 返回 true，避免 NPE。
 */
class FakeClientConnection(private val server: MinecraftServer) : Connection(PacketFlow.CLIENTBOUND) {

    init {
        // 设置一个 EmbeddedChannel，使 channel 不为 null
        val channel = EmbeddedChannel()
        // 通过反射设置 channel 字段
        try {
            val field = Connection::class.java.getDeclaredField("channel")
            field.isAccessible = true
            field.set(this, channel)
        } catch (_: Exception) {
            // 忽略
        }
    }

    override fun getReceiving(): PacketFlow = PacketFlow.SERVERBOUND

    override fun send(packet: Packet<*>) {
        broadcastPacket(packet)
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) {
        broadcastPacket(packet)
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        broadcastPacket(packet)
    }

    override fun isConnected(): Boolean = true

    override fun <T : PacketListener> setupInboundProtocol(
        protocol: net.minecraft.network.ProtocolInfo<T>,
        packetListener: T
    ) {
        // 直接设置监听器，跳过验证
        try {
            val field = Connection::class.java.getDeclaredField("packetListener")
            field.isAccessible = true
            field.set(this, packetListener)
        } catch (_: Exception) {
            // 忽略
        }
    }

    override fun setupOutboundProtocol(protocol: net.minecraft.network.ProtocolInfo<*>) {
        // 无操作
    }

    private fun broadcastPacket(packet: Packet<*>) {
        try {
            server.execute {
                for (player in server.playerList.players) {
                    if (player !is FakePlayer) {
                        player.connection.send(packet)
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略广播错误
        }
    }
}
