package org.darkan.lobby.server.packet.social

import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.lobby.command.LobbyCommands
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class ChatHandler : PacketHandler<LobbyPlayer, Chat> {
    override suspend fun handle(player: LobbyPlayer, packet: Chat) {
        // The legacy lobby also accepted commands typed into chat as `::name` / `;;name`.
        val message = packet.message
        if (message.startsWith("::") || message.startsWith(";;")) {
            if (!LobbyCommands.process(player, message.substring(2)))
                player.message("Unknown command: $message")
            return
        }
        SocialGateway.handleClientPacket(player.account.username, packet)
    }
}
