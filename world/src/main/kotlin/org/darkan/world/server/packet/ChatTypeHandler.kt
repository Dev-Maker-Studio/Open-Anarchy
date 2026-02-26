package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.ChatType
import org.darkan.core.net.prot.handler.PacketHandler

class ChatTypeHandler : PacketHandler<Player, ChatType> {
    override suspend fun handle(player: Player, packet: ChatType) {
        player.chatType = packet.type
    }
}
