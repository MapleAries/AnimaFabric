package com.maple.entity

import io.netty.channel.ChannelFutureListener
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer

/**
 * 假玩家的网络连接。
 * 重写 getReceiving() 返回 SERVERBOUND，使 placeNewPlayer() 能正常设置监听器。
 * send() 将数据包广播给所有真实客户端。
 */
class FakeClientConnection(private val server: MinecraftServer) : Connection(PacketFlow.CLIENTBOUND) {

    /**
     * 关键：返回 SERVERBOUND 使 setupInboundProtocol() 不会抛出异常。
     */
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
