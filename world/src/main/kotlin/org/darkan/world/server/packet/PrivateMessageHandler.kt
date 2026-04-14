package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.PrivateMessage
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.server.WorldServer

class PrivateMessageHandler : PacketHandler<Player, PrivateMessage> {
    override suspend fun handle(player: Player, packet: PrivateMessage) {
        if (!player.hasStarted()) return
        if (player.account.checkMuted()) {
            player.sendMessage("You are muted.")
            return
        }
        WorldServer.forwardToLobby(player.username, packet)
    }
}
