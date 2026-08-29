package org.darkan.world.server.packet

import com.rs.engine.command.Commands
import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.social.PublicChatMessage
import org.darkan.world.server.WorldServer

class ChatHandler : PacketHandler<Player, Chat> {
    override suspend fun handle(player: Player, packet: Chat) {
        if (!player.hasStarted()) return

        val message = packet.message
        if (message.startsWith("::") || message.startsWith(";;")) {
            Commands.processCommand(player, message.substring(2), false, false)
            return
        }

        if (player.account.checkMuted()) {
            player.sendMessage("You are muted.")
            return
        }

        // Chat types 1-3 are forwarded to lobby (friends chat, private, clan)
        if (packet.type in 1..3) {
            WorldServer.forwardToLobby(player.username, packet)
            return
        }

        // Dungeon party chat
        val dungController = player.controllerManager.controller
        if (dungController != null && dungController::class.java.simpleName == "DungeonController") {
            try {
                val method = dungController.javaClass.getMethod("sendPartyMessage", Player::class.java, String::class.java)
                method.invoke(dungController, player, packet.message)
                return
            } catch (_: Exception) {}
        }

        // Public chat
        if (message.isBlank()) return
        val effects = (packet.color shl 8) or (packet.effect and 0xff)
        player.sendPublicChatMessage(PublicChatMessage(message, effects))
    }
}
