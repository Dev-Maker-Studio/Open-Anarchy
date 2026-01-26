package org.darkan.world.server.packet

import org.darkan.core.net.prot.FcJoin
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class FcJoinHandler : PacketHandler<WorldPlayer, FcJoin> {
    override suspend fun handle(player: WorldPlayer, packet: FcJoin) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


