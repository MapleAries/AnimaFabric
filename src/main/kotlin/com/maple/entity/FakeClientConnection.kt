package com.maple.entity

import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer

/**
 * 假玩家的网络连接。
 * 将数据包广播给所有真实客户端，使假玩家在客户端可见。
 */
class FakeClientConnection(private val server: MinecraftServer) : Connection(PacketFlow.CLIENTBOUND) {

    override fun send(packet: Packet<*>) {
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
