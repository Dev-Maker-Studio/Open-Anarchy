package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.RemoveFriend
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class RemoveFriendHandler : PacketHandler<LobbyPlayer, RemoveFriend> {
    override suspend fun handle(player: LobbyPlayer, packet: RemoveFriend) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


