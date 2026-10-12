package org.darkan.world.server.packet

import com.rs.engine.command.Commands
import com.rs.game.content.skills.dungeoneering.DungeonController
import com.rs.game.model.entity.player.Player
import org.darkan.core.clan.ClanAction
import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.social.PublicChatMessage
import org.darkan.core.social.SocialAction
import org.darkan.world.clan.ClanRegistry
import org.darkan.world.server.WorldServer

class ChatHandler : PacketHandler<Player, Chat> {
    override suspend fun handle(player: Player, packet: Chat) {
        if (!player.hasStarted()) return

        val message = packet.message
        if (message.startsWith("::") || message.startsWith(";;")) {
            Commands.processCommand(player, message.substring(2), false, false)
            return
        }

        val chatType = player.chatType
        if (chatType == 2 || chatType == 3) {
            if (message.isBlank()) return
            val effects = (packet.color shl 8) or (packet.effect and 0xff)
            ClanRegistry.send(
                player,
                ClanAction.Chat(guest = chatType == 3, message = PublicChatMessage(message, effects)),
            )
            return
        }
        if (message.isBlank()) return
        if (chatType == 1) {
            val effects = (packet.color shl 8) or (packet.effect and 0xff)
            WorldServer.act(player.username, SocialAction.SayInFriendsChat(PublicChatMessage(message, effects)))
            return
        }

        if (player.account.checkMuted()) {
            player.sendMessage("You are muted. The mute will be lifted at ${player.account.getUnmuteDate()}")
            return
        }

        val effects = (packet.color shl 8) or (packet.effect and 0xff)
        val chatMessage = PublicChatMessage(message, effects)
        val controller = player.controllerManager.controller
        if (controller is DungeonController) {
            for (party in player.dungManager.party.team)
                party.packets.sendPublicMessage(player, chatMessage)
            return
        }
        player.sendPublicChatMessage(chatMessage)
    }
}
