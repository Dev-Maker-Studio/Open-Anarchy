package org.darkan.lobby.server.packet

import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class IfButtonHandler : PacketHandler<LobbyPlayer, IfButton> {
    override suspend fun handle(player: LobbyPlayer, packet: IfButton) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}
