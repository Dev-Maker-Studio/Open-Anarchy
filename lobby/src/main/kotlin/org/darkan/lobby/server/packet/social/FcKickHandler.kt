package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.packet.FcKick
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class FcKickHandler : PacketHandler<LobbyPlayer, FcKick> {
    override suspend fun handle(player: LobbyPlayer, packet: FcKick) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


