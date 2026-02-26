package org.darkan.world.server.packet

import com.rs.engine.pathfinder.RouteEvent
import com.rs.game.content.skills.firemaking.Firemaking
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.IfOnGroundItem
import org.darkan.core.net.prot.handler.PacketHandler
import world.gregs.voidps.type.Tile

class IfOnGroundItemHandler : PacketHandler<Player, IfOnGroundItem> {
    override suspend fun handle(player: Player, packet: IfOnGroundItem) {
        if (!player.hasStarted() || !player.isRunning || player.isDead || player.isLocked)
            return
        if (!player.interfaceManager.topOpen(packet.interfaceId))
            return
        val tile = Tile.of(packet.x, packet.y, player.plane)
        if (!player.mapChunkIds.contains(tile.getChunkId()))
            return
        val groundItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return

        player.stopAll(false)
        if (packet.forceRun)
            player.setRun(true)

        // Tinderbox on logs => firemaking
        if (packet.itemIdContainer == 590) {
            val fire = Firemaking.Fire.forId(groundItem.id)
            if (fire != null) {
                player.setRouteEvent(RouteEvent(groundItem) {
                    player.actionManager.setAction(Firemaking(fire, groundItem))
                })
                return
            }
        }
    }
}
