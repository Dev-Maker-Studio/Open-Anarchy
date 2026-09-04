package org.darkan.lobby.server.packet.social

import org.darkan.core.clan.ClanAction
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.packet.QuickChatPublic
import org.darkan.core.social.QuickChatMessage
import org.darkan.lobby.social.ClanService
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.LobbyPlayer

class QuickChatPublicHandler : PacketHandler<LobbyPlayer, QuickChatPublic> {
    override suspend fun handle(player: LobbyPlayer, packet: QuickChatPublic) {
        val username = player.account.username
        val type = SocialGateway.quickChatTypeOf(username, packet.chatType)
        if (type == ChatHandler.CLAN_CHAT || type == ChatHandler.GUEST_CLAN_CHAT) {
            val message = QuickChatMessage(message = "", fileId = packet.qcId, data = packet.messageData)
            val action = ClanAction.QuickChat(guest = type == ChatHandler.GUEST_CLAN_CHAT, message = message)
            val result = ClanService.handle(username, action)
            result.message?.let { player.message(it) }
            return
        }
        SocialGateway.handleClientPacket(username, packet)
    }
}
