package org.darkan.world.server.packet

import org.darkan.core.net.prot.ChatSetFilter
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class ChatSetFilterHandler : PacketHandler<WorldPlayer, ChatSetFilter> {
    override suspend fun handle(player: WorldPlayer, packet: ChatSetFilter) {
        // Forward the decoded packet to SocialGateway; gateway will persist and echo as needed.
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


