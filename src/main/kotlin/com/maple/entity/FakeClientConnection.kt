package com.maple.entity

import io.netty.channel.ChannelFutureListener
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer

/**
 * 假玩家的网络连接。
 * 绕过所有验证，使 placeNewPlayer() 能正常工作。
 */
class FakeClientConnection(private val server: MinecraftServer) : Connection(PacketFlow.CLIENTBOUND) {

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

    /**
     * 绕过 setupInboundProtocol 的验证。
     * 直接设置 packetListener 字段。
     */
    override fun <T : PacketListener> setupInboundProtocol(
        protocol: net.minecraft.network.ProtocolInfo<T>,
        packetListener: T
    ) {
        // 直接设置监听器，跳过验证
        // 使用反射或直接调用父类的无验证路径
        try {
            val field = Connection::class.java.getDeclaredField("packetListener")
            field.isAccessible = true
            field.set(this, packetListener)
        } catch (_: Exception) {
            // 如果反射失败，尝试其他方式
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
