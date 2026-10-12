package org.darkan.lobby.server.packet

import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.packet.ClientCheat
import org.darkan.lobby.command.LobbyCommands
import org.darkan.lobby.web.model.LobbyPlayer

class ClientCheatHandler : PacketHandler<LobbyPlayer, ClientCheat> {
    override suspend fun handle(player: LobbyPlayer, packet: ClientCheat) {
        if (!LobbyCommands.process(player, packet.command))
            player.message("Unknown command: ::${packet.command}")
    }
}
