package org.darkan.world.server.packet

import com.rs.engine.pathfinder.RouteEvent
import com.rs.game.World
import com.rs.game.content.Effect
import com.rs.game.content.minigames.treasuretrails.TreasureTrailsManager
import com.rs.game.content.skills.firemaking.Firemaking
import com.rs.game.content.skills.hunter.BoxAction
import com.rs.game.content.skills.hunter.BoxTrapType
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import com.rs.plugin.PluginManager
import com.rs.plugin.events.PickupItemEvent
import com.rs.utils.ItemConfig
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.net.prot.packet.OpGroundItem
import org.darkan.core.net.prot.handler.PacketHandler
import world.gregs.voidps.type.Tile

class OpGroundItemHandler : PacketHandler<Player, OpGroundItem> {
    override suspend fun handle(player: Player, packet: OpGroundItem) {
        if (!player.hasStarted() || !player.clientHasLoadedMapRegion() || player.isDead)
            return
        if (player.isLocked || player.hasEffect(Effect.FREEZE))
            return
        val tile = Tile.of(packet.x, packet.y, player.plane)
        if (!player.mapChunkIds.contains(tile.getChunkId()))
            return
        val item = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return

        if (packet.opNum == 6) {
            val def = ItemDefinitions.getDefs(item.id)
            if (item.getMetaData("combatCharges") != null)
                player.sendMessage("<col=FF0000>It looks like it will last another ${Utils.ticksToTime(item.getMetaDataI("combatCharges", 0).toDouble())}")
            player.packets.sendGroundItemMessage(item, "${ItemConfig.get(item.id).getExamine(item.item)} General store: ${Utils.formatTypicalInteger(def.getSellPrice())} High Alchemy: ${Utils.formatTypicalInteger(def.getHighAlchPrice())}")
            return
        }

        player.stopAll()
        if (packet.forceRun)
            player.setRun(true)

        when (packet.opNum) {
            3 -> player.setRouteEvent(RouteEvent(item, {
                val groundItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return@RouteEvent
                if (!player.controllerManager.canTakeItem(groundItem)) return@RouteEvent
                if (TreasureTrailsManager.isScroll(groundItem.id))
                    if (player.treasureTrailsManager.hasClueScrollItem()) {
                        player.sendMessage("You should finish the clue you are currently doing first.")
                        return@RouteEvent
                    }
                val e2 = PickupItemEvent(player, groundItem, false)
                PluginManager.handle(e2)
                if (!e2.isCancelPickup)
                    World.removeGroundItem(player, groundItem, true)
            }, {
                val groundItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player)
                if (groundItem == null || !player.controllerManager.canTakeItem(groundItem))
                    return@RouteEvent true
                if (!player.lineOfSightTo(groundItem.tile, true)) {
                    player.sendMessage("You can't reach that.")
                    return@RouteEvent true
                }
                player.anim(832)
                player.faceTile(groundItem.tile)
                val e1 = PickupItemEvent(player, groundItem, false)
                PluginManager.handle(e1)
                if (!e1.isCancelPickup) {
                    player.soundEffect(2582, false)
                    World.removeGroundItem(player, groundItem, true)
                }
                true
            }))
            4 -> player.setRouteEvent(RouteEvent(item) {
                val groundItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return@RouteEvent
                val fire = Firemaking.Fire.forId(groundItem.id)
                if (fire != null) {
                    player.actionManager.setAction(Firemaking(fire, groundItem))
                    return@RouteEvent
                }
                val trap = BoxTrapType.forId(groundItem.id)
                if (trap != null)
                    player.actionManager.setAction(BoxAction(trap, groundItem))
            })
        }
    }
}
