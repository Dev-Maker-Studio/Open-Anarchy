package org.darkan.lobby.server.packet

import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.packet.ResumeTextDialog
import org.darkan.lobby.social.LobbyClanScreen
import org.darkan.lobby.web.model.LobbyPlayer

class ResumeTextDialogHandler : PacketHandler<LobbyPlayer, ResumeTextDialog> {
    override suspend fun handle(player: LobbyPlayer, packet: ResumeTextDialog) {
        LobbyClanScreen.nameInput(player, packet.text)
    }
}
