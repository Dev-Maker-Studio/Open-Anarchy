package org.darkan.world.server.packet

import org.darkan.core.net.prot.AddIgnoreReq
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class AddIgnoreReqHandler : PacketHandler<WorldPlayer, AddIgnoreReq> {
    override suspend fun handle(player: WorldPlayer, packet: AddIgnoreReq) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


