package org.darkan.world.server.packet

import org.darkan.core.net.prot.FcKick
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class FcKickHandler : PacketHandler<WorldPlayer, FcKick> {
    override suspend fun handle(player: WorldPlayer, packet: FcKick) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


