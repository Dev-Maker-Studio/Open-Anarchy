package org.darkan.world.server.packet

import com.rs.game.content.Effect
import com.rs.game.content.claims.ClaimManager
import com.rs.game.content.items.handleModelPotatoClick
import com.rs.game.content.skills.construction.handleFurniturePlacementClick
import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.Walk
import org.darkan.core.net.prot.handler.PacketHandler
import world.gregs.voidps.type.Tile

class WalkHandler : PacketHandler<Player, Walk> {
    override suspend fun handle(player: Player, packet: Walk) {
        player.refreshIdleTime()
        if (!player.hasStarted() || !player.clientHasLoadedMapRegion() || player.isDead || player.isLocked)
            return
        if (player.hasEffect(Effect.FREEZE)) {
            player.sendMessage("A magical force prevents you from moving.")
            return
        }
        if (player.hasEffect(Effect.STUN)) {
            player.sendMessage("You are stunned.")
            return
        }
        if (ClaimManager.handleClick(player, Tile.of(packet.x, packet.y, player.plane)))
            return
        if (handleModelPotatoClick(player, Tile.of(packet.x, packet.y, player.plane)))
            return
        if (handleFurniturePlacementClick(player, Tile.of(packet.x, packet.y, player.plane)))
            return
        player.stopAll()
        player.walkRequest = packet
    }
}
