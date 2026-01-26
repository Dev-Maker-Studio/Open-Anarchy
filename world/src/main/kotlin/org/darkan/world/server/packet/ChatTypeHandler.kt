package org.darkan.world.server.packet

import org.darkan.core.net.prot.ChatType
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class ChatTypeHandler : PacketHandler<WorldPlayer, ChatType> {
    override suspend fun handle(player: WorldPlayer, packet: ChatType) {
        player.chatType = packet.type
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


