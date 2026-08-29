package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class ChatHandler : PacketHandler<LobbyPlayer, Chat> {
    override suspend fun handle(player: LobbyPlayer, packet: Chat) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


