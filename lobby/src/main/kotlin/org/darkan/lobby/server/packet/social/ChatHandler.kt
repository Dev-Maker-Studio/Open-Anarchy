package org.darkan.lobby.server.packet.social

import org.darkan.core.clan.ClanAction
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.social.PublicChatMessage
import org.darkan.lobby.command.LobbyCommands
import org.darkan.lobby.social.ClanService
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
        val username = player.account.username
        val type = SocialGateway.chatTypeOf(username, packet.type)
        if (type == CLAN_CHAT || type == GUEST_CLAN_CHAT) {
            if (message.isBlank()) return
            val effects = (packet.color shl 8) or (packet.effect and 0xff)
            val action = ClanAction.Chat(guest = type == GUEST_CLAN_CHAT, message = PublicChatMessage(message, effects))
            val result = ClanService.handle(username, action)
            result.message?.let { player.message(it) }
            return
        }
        SocialGateway.handleClientPacket(username, packet)
    }

    companion object {
        /** The client's chat types: 1 is the friends chat, 2 the clan channel, 3 the guest clan channel. */
        const val CLAN_CHAT = 2
        const val GUEST_CLAN_CHAT = 3
    }
}
