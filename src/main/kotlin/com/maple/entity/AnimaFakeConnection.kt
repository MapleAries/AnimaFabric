package com.maple.entity

import io.netty.channel.ChannelFutureListener
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import java.net.SocketAddress

class AnimaFakeConnection : Connection(PacketFlow.SERVERBOUND) {
    private val address = object : SocketAddress() {}

    override fun send(packet: Packet<*>) {
        // Server-side bot: there is no client to receive packets.
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) {
        // Server-side bot: there is no client to receive packets.
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        // Server-side bot: there is no client to receive packets.
    }

    override fun flushChannel() {
        // No backing network channel.
    }

    override fun getRemoteAddress(): SocketAddress {
        return address
    }

    override fun getLoggableAddress(useRemoteAddress: Boolean): String {
        return "anima-fake-connection"
    }

    override fun disconnect(details: DisconnectionDetails) {
        // PlayerList.remove handles lifecycle cleanup.
    }

    override fun isConnected(): Boolean {
        return true
    }

    override fun isMemoryConnection(): Boolean {
        return true
    }
}
