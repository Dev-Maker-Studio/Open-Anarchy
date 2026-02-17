package org.darkan.world.server.packet

import org.darkan.core.net.prot.ClanChannelKickUser
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class ClanChannelKickUserHandler : PacketHandler<WorldPlayer, ClanChannelKickUser> {
    override suspend fun handle(player: WorldPlayer, packet: ClanChannelKickUser) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}
