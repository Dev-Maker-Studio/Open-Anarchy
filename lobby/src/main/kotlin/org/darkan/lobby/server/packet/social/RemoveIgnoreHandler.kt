package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.RemoveIgnore
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class RemoveIgnoreHandler : PacketHandler<LobbyPlayer, RemoveIgnore> {
    override suspend fun handle(player: LobbyPlayer, packet: RemoveIgnore) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


