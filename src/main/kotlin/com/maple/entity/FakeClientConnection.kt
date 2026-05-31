package com.maple.entity

import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer

/**
 * 假玩家的网络连接。
 * send() 将数据包广播给所有真实客户端，使假玩家在客户端可见。
 */
class FakeClientConnection : Connection(PacketFlow.CLIENTBOUND) {

    override fun send(packet: Packet<*>, listener: net.minecraft.network.PacketListener?) {
        // 广播给所有真实客户端
        broadcastPacket(packet)
    }

    override fun send(packet: Packet<*>) {
        broadcastPacket(packet)
    }

    override fun isOpen(): Boolean = true

    override fun setupOutboundProtocol(protocol: net.minecraft.network.ProtocolInfo<*, *>) {
        // 无操作
    }

    companion object {
        /**
         * 将数据包广播给所有在线的真实玩家。
         */
        private fun broadcastPacket(packet: Packet<*>) {
            try {
                val server = net.minecraft.server.MinecraftServer.getServer() ?: return
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
}
