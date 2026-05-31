package com.maple.entity

import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/**
 * 无操作的网络连接，用于假玩家。
 * send() 不执行任何操作，isOpen() 始终返回 true。
 */
class FakeClientConnection : Connection(PacketFlow.CLIENTBOUND) {

    override fun send(packet: Packet<*>, listener: PacketListener?) {
        // 无操作
    }

    override fun send(packet: Packet<*>) {
        // 无操作
    }

    override fun isOpen(): Boolean = true

    override fun setupOutboundProtocol(protocol: net.minecraft.network.ProtocolInfo<*, *>) {
        // 无操作
    }
}
