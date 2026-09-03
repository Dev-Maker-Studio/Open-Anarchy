package org.darkan.world.server.packet

import com.rs.engine.pathfinder.RouteEvent
import com.rs.game.World
import com.rs.game.content.Effect
import com.rs.game.content.minigames.treasuretrails.TreasureTrailsManager
import com.rs.game.content.skills.firemaking.Firemaking
import com.rs.game.content.skills.magic.Magic
import com.rs.game.content.skills.magic.Rune
import com.rs.game.content.skills.magic.RuneSet
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.actions.PlayerAction
import com.rs.plugin.PluginManager
import com.rs.plugin.events.PickupItemEvent
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.model.SpotAnim
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.packet.IfOnGroundItem
import world.gregs.voidps.type.Tile

class IfOnGroundItemHandler : PacketHandler<Player, IfOnGroundItem> {
    override suspend fun handle(player: Player, packet: IfOnGroundItem) {
        if (Utils.getInterfaceDefinitionsSize() <= packet.interfaceId || player.isDead || !player.interfaceManager.topOpen(packet.interfaceId))
            return
        if (packet.componentId != 65535 && Utils.getInterfaceDefinitionsComponentsSize(packet.interfaceId) <= packet.componentId)
            return
        val tile = Tile.of(packet.x, packet.y, player.plane)
        if (!player.mapChunkIds.contains(tile.getChunkId()))
            return
        val groundItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return

        player.stopAll()
        if (packet.forceRun)
            player.setRun(true)

        if (packet.interfaceId == 679) {
            val item = player.inventory.getItem(packet.slotId) ?: return
            player.setRouteEvent(RouteEvent(groundItem) {
                if (item.id == 590) {
                    val fire = Firemaking.Fire.forId(groundItem.id)
                    if (fire != null)
                        player.actionManager.setAction(Firemaking(fire, groundItem))
                }
            })
        } else if (packet.interfaceId == 192 && packet.componentId == 44) {
            player.actionManager.setAction(TelekineticGrabAction(packet, tile))
        }
    }
}

/**
 * Telekinetic Grab (spellbook 192, component 44).
 *
 * Ported from the legacy anonymous [PlayerAction] in
 * `com.rs.net.decoders.handlers.impl.interfaces.IFOnGroundItemHandler` (lines 70-134).
 */
private class TelekineticGrabAction(private val packet: IfOnGroundItem, private val tile: Tile) : PlayerAction() {

    override fun start(player: Player): Boolean = true

    override fun process(player: Player): Boolean {
        if (player.isDead || player.hasFinished())
            return false
        val item = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player) ?: return false
        if (player.plane != tile.level)
            return false
        if (player.hasEffect(Effect.FREEZE))
            return true
        if (!player.lineOfSightTo(tile, false) || Utils.getDistance(player.tile, tile) > 8) {
            if (player.hasWalkSteps())
                player.resetWalkSteps()
            player.calcFollow(tile, 25, true)
            return true
        }
        if (TreasureTrailsManager.isScroll(item.id) && player.treasureTrailsManager.hasClueScrollItem()) {
            player.sendMessage("You should finish the clue you are currently doing first.")
            return false
        }
        if (player.canCastSpell() && Magic.checkMagicAndRunes(player, 33, true, RuneSet(Rune.AIR, 1, Rune.LAW, 1))) {
            setActionDelay(player, 3)
            player.addSpellDelay(2)
            player.resetWalkSteps()
            player.faceTile(tile)
            player.anim(711)
            player.skills.addXp(Constants.MAGIC, 43.0)
            player.spotAnim(SpotAnim(142, 2, 50, Utils.getAngleTo(tile.x - player.x, tile.y - player.y)))
            World.sendProjectile(player, tile, 143, 35 to 0, 60, 10, 0, 0) {
                val gItem = ChunkManager.getChunk(tile.getChunkId()).getGroundItem(packet.itemId, tile, player)
                if (gItem == null) {
                    player.sendMessage("Too late. It's gone!")
                } else {
                    World.sendSpotAnim(tile, SpotAnim(144))
                    val e2 = PickupItemEvent(player, gItem, true)
                    PluginManager.handle(e2)
                    if (!e2.isCancelPickup)
                        World.removeGroundItem(player, gItem, true)
                }
            }
        }
        return false
    }

    override fun processWithDelay(player: Player): Int = 0

    override fun stop(player: Player) {}
}
