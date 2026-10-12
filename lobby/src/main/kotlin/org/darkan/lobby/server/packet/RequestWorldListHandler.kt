package org.darkan.lobby.server.packet

import org.darkan.core.net.prot.packet.RequestWorldList
import org.darkan.core.net.prot.packet.WorldListPacket
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.Lobby
import org.darkan.lobby.web.model.LobbyPlayer

class RequestWorldListHandler : PacketHandler<LobbyPlayer, RequestWorldList> {
    override suspend fun handle(player: LobbyPlayer, packet: RequestWorldList) {
        val upToDate = packet.worldlistVersion == Lobby.worldList.revision
        player.session.send(WorldListPacket(Lobby.worldList.snapshot(), refreshOnClient = true, refreshOnlyPlayerCounts = upToDate))
    }
}