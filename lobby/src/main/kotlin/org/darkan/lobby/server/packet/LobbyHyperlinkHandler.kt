package org.darkan.lobby.server.packet

import org.darkan.core.Logger.logDebug
import org.darkan.core.net.prot.packet.LobbyHyperlink
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.web.model.LobbyPlayer

class LobbyHyperlinkHandler : PacketHandler<LobbyPlayer, LobbyHyperlink> {
    override suspend fun handle(player: LobbyPlayer, packet: LobbyHyperlink) {
        logDebug("Lobby hyperlink from ${player.account.username}: service=${packet.service} page=${packet.page} flags=${packet.flags}")
    }
}
