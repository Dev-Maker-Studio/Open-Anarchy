package org.darkan.world.server.packet

import com.rs.engine.pathfinder.RouteEvent
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.net.decoders.handlers.ObjectHandler
import com.rs.plugin.PluginManager
import com.rs.plugin.events.InterfaceOnObjectEvent
import org.darkan.core.model.Item
import org.darkan.core.net.prot.IfOnObject
import org.darkan.core.net.prot.handler.PacketHandler
import world.gregs.voidps.type.Tile

class IfOnObjectHandler : PacketHandler<Player, IfOnObject> {
    override suspend fun handle(player: Player, packet: IfOnObject) {
        if (!player.hasStarted() || !player.isRunning || player.isDead || player.isLocked)
            return
        if (!player.interfaceManager.topOpen(packet.interfaceId))
            return
        val tile = Tile.of(packet.x, packet.y, player.plane)
        if (!player.mapChunkIds.contains(tile.getChunkId()))
            return
        val obj = World.getObjectWithId(tile, packet.objectId) ?: return

        player.stopAll(false)
        if (packet.forceRun)
            player.setRun(true)

        if (packet.interfaceId == 679 || packet.interfaceId == 747) {
            // Familiar special
            player.setRouteEvent(RouteEvent(obj) {
                PluginManager.handle(InterfaceOnObjectEvent(player, obj, packet.interfaceId, packet.componentId, packet.slotId, true))
            })
        } else if (packet.interfaceId == 193 || packet.interfaceId == 430 || packet.interfaceId == 192 || packet.interfaceId == 662) {
            // Spell on object / familiar
            player.setRouteEvent(RouteEvent(obj) {
                PluginManager.handle(InterfaceOnObjectEvent(player, obj, packet.interfaceId, packet.componentId, packet.slotId, true))
            })
        } else {
            // Item on object (inventory)
            val item = Item(packet.itemId)
            ObjectHandler.handleItemOnObject(player, obj, packet.interfaceId, item, packet.slotId)
        }
    }
}
