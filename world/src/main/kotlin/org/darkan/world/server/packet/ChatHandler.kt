package org.darkan.world.server.packet

import org.darkan.core.net.prot.Chat
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class ChatHandler : PacketHandler<WorldPlayer, Chat> {
    override suspend fun handle(player: WorldPlayer, packet: Chat) {
        val msg = packet.message
        if (msg.isBlank()) return

        // Forward decoded chat packet to gateway. Gateway will interpret chat channel type (packet.type or stored ChatType).
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


