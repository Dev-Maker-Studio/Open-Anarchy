package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.packet.ClanChannelKickUser
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class CcKickHandler : PacketHandler<LobbyPlayer, ClanChannelKickUser> {
    override suspend fun handle(player: LobbyPlayer, packet: ClanChannelKickUser) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}
