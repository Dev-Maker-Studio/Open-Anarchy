package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.ChatType
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.server.WorldServer

class ChatTypeHandler : PacketHandler<Player, ChatType> {
    override suspend fun handle(player: Player, packet: ChatType) {
        player.chatType = packet.type
        WorldServer.forwardToLobby(player.username, packet)
    }
}
