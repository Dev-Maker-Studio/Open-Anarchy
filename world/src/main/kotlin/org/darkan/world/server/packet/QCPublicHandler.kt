package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.QuickChatPublic
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.server.WorldServer

class QCPublicHandler : PacketHandler<Player, QuickChatPublic> {
    override suspend fun handle(player: Player, packet: QuickChatPublic) {
        if (!player.hasStarted()) return
        // Chat types 1-3 forwarded to lobby
        if (packet.chatType in 1..3) {
            WorldServer.forwardToLobby(player.username, packet)
            return
        }
        // TODO: Quick chat public message handling
    }
}
