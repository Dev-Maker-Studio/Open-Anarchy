package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.AddFriend
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class AddFriendHandler : PacketHandler<LobbyPlayer, AddFriend> {
    override suspend fun handle(player: LobbyPlayer, packet: AddFriend) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}


