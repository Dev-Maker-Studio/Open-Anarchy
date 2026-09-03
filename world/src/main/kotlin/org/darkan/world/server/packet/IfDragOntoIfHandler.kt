package org.darkan.world.server.packet

import com.rs.engine.Shop
import com.rs.game.model.entity.player.Inventory
import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.IfDragOntoIf
import org.darkan.core.net.prot.handler.PacketHandler

/**
 * Dragging one interface component onto another: rearranging the inventory, the bank, the notes
 * tab, and drag-to-buy in shops.
 */
class IfDragOntoIfHandler : PacketHandler<Player, IfDragOntoIf> {
    override suspend fun handle(player: Player, packet: IfDragOntoIf) {
        if (!player.hasStarted() || !player.isRunning || player.isDead || player.isLocked)
            return
        if (!player.interfaceManager.topOpen(packet.fromInter) || !player.interfaceManager.topOpen(packet.toInter))
            return

        val invSize = player.inventory.itemsContainerSize
        when {
            // Inventory tab. The client numbers this interface's slots from 28.
            packet.fromInter == Inventory.INVENTORY_INTERFACE && packet.fromComp == 0 &&
                packet.toInter == Inventory.INVENTORY_INTERFACE && packet.toComp == 0 -> {
                player.closeInterfaces()
                val to = packet.toSlot - 28
                if (to < 0 || to >= invSize || packet.fromSlot < 0 || packet.fromSlot >= invSize)
                    return
                player.inventory.switchItem(packet.fromSlot, to)
            }
            // The inventory as drawn inside the bank screen.
            packet.fromInter == 763 && packet.fromComp == 0 && packet.toInter == 763 && packet.toComp == 0 -> {
                if (packet.toSlot < 0 || packet.toSlot >= invSize || packet.fromSlot < 0 || packet.fromSlot >= invSize)
                    return
                player.inventory.switchItem(packet.fromSlot, packet.toSlot)
            }
            // The bank. A toSlot of -1 is a drag onto a tab header, which is how a tab is created,
            // so only a real slot is range-checked - the header's component id is validated by
            // `Bank.switchItem` itself.
            packet.fromInter == 762 && packet.toInter == 762 -> {
                val bankSize = player.bank.bankSize
                if (packet.fromSlot < 0 || packet.fromSlot >= bankSize)
                    return
                if (packet.toSlot != -1 && (packet.toSlot < 0 || packet.toSlot >= bankSize))
                    return
                player.bank.switchItem(packet.fromSlot, packet.toSlot, packet.fromComp, packet.toComp)
            }
            // Drag a shop stock slot onto the inventory to buy one.
            packet.fromInter == 1265 && packet.toInter == 1266 && player.tempAttribs.getB("shop_buying") -> {
                val shop = player.tempAttribs.getO<Shop>("Shop") ?: return
                shop.buy(player, packet.fromSlot, 1)
            }
            packet.fromInter == 34 && packet.toInter == 34 ->
                player.notes.switchNotes(packet.fromSlot, packet.toSlot)
        }
    }
}
