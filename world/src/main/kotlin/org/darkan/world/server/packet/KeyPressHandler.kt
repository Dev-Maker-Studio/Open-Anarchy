package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.KeyPress
import org.darkan.core.net.prot.handler.PacketHandler

class KeyPressHandler : PacketHandler<Player, KeyPress> {
    override suspend fun handle(player: Player, packet: KeyPress) {
        if (packet.keyCode == 13) { // Enter key
            if (player.interfaceManager.containsScreenInter())
                player.closeInterfaces()
            if (player.tempAttribs.getB("worldMap"))
                player.packets.sendVarc(622, 0)
        }
    }
}
