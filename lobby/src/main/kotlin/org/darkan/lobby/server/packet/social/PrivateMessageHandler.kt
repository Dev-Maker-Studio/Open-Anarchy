package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.PrivateMessage
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class PrivateMessageHandler : PacketHandler<LobbyPlayer, PrivateMessage> {
    override suspend fun handle(player: LobbyPlayer, packet: PrivateMessage) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


