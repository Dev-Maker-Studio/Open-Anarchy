package org.darkan.world.server.packet

import org.darkan.core.net.prot.RemoveIgnore
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class RemoveIgnoreHandler : PacketHandler<WorldPlayer, RemoveIgnore> {
    override suspend fun handle(player: WorldPlayer, packet: RemoveIgnore) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


