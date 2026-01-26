package org.darkan.world.server.packet

import org.darkan.core.net.prot.AddFriend
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.model.WorldPlayer

class AddFriendHandler : PacketHandler<WorldPlayer, AddFriend> {
    override suspend fun handle(player: WorldPlayer, packet: AddFriend) {
        player.socialClient.sendClientPacket(player.account.username, packet)
    }
}


