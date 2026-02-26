package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.InterfaceManager
import org.darkan.core.net.prot.IfDragOntoIf
import org.darkan.core.net.prot.handler.PacketHandler

class IfDragOntoIfHandler : PacketHandler<Player, IfDragOntoIf> {
    override suspend fun handle(player: Player, packet: IfDragOntoIf) {
        if (!player.hasStarted() || !player.isRunning || player.isDead || player.isLocked)
            return

        // Inventory switching
        if ((packet.fromInter == 149 && packet.toInter == 149) ||
            (packet.fromInter == 670 && packet.toInter == 670)) {
            if (packet.toSlot < 0 || packet.toSlot >= 28 || packet.fromSlot < 0 || packet.fromSlot >= 28)
                return
            player.inventory.switchItem(packet.fromSlot, packet.toSlot)
        }

        // Bank switching
        if (packet.fromInter == 762 && packet.toInter == 762) {
            player.bank.switchItem(packet.fromSlot, packet.toSlot, packet.fromComp, packet.toComp)
        }

        // Bank insert/swap from inventory
        if (packet.fromInter == 763 && packet.toInter == 762) {
            player.bank.switchItem(packet.fromSlot, packet.toSlot, packet.fromComp, packet.toComp)
        }
    }
}
