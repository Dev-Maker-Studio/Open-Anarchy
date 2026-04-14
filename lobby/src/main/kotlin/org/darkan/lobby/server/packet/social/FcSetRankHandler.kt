package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.FcSetRank
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class FcSetRankHandler : PacketHandler<LobbyPlayer, FcSetRank> {
    override suspend fun handle(player: LobbyPlayer, packet: FcSetRank) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


