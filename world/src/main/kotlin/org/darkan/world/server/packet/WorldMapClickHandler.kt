package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.WorldMapClick
import org.darkan.core.net.prot.handler.PacketHandler

class WorldMapClickHandler : PacketHandler<Player, WorldMapClick> {
    override suspend fun handle(player: Player, packet: WorldMapClick) {
        val tile = packet.tile
        val tileHash = tile.x or (tile.y shl 14) or (tile.plane shl 28)
        val lastHash = player.tempAttribs.getI("worldMapTileHash", -1)
        if (lastHash == tileHash) {
            player.hintIconsManager.addHintIcon(tile.x, tile.y, tile.plane, -1, 2, 0, -1, false)
            player.vars.setVar(1159, tileHash)
        }
        player.tempAttribs.setI("worldMapTileHash", tileHash)
    }
}
