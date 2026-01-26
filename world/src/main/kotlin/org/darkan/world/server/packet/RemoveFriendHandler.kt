package org.darkan.world.server.packet

import org.darkan.core.net.prot.RemoveFriend
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class RemoveFriendHandler : PacketHandler<WorldPlayer, RemoveFriend> {
    override suspend fun handle(player: WorldPlayer, packet: RemoveFriend) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


