package org.darkan.world.server.packet

import org.darkan.core.net.prot.FcSetRank
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class FcSetRankHandler : PacketHandler<WorldPlayer, FcSetRank> {
    override suspend fun handle(player: WorldPlayer, packet: FcSetRank) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


