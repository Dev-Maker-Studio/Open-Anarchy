package org.darkan.lobby.server.packet

import org.darkan.core.net.prot.packet.ResumeClanForumQFCDialog
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class ResumeClanForumQfcDialogHandler : PacketHandler<LobbyPlayer, ResumeClanForumQFCDialog> {
    override suspend fun handle(player: LobbyPlayer, packet: ResumeClanForumQFCDialog) {
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}
