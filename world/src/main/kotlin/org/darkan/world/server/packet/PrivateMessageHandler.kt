package org.darkan.world.server.packet

import org.darkan.core.net.prot.PrivateMessage
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class PrivateMessageHandler : PacketHandler<WorldPlayer, PrivateMessage> {
    override suspend fun handle(player: WorldPlayer, packet: PrivateMessage) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


