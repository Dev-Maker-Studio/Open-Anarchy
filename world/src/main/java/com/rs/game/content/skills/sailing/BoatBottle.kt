package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import org.darkan.core.model.Item

private const val BOAT_KEY = "boatBottleId"

object BoatBottles {
    fun bottle(player: Player, slot: Int) {
        val site = PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS) ?: return player.sendMessage("You need to be at a port to bottle a boat.")
        Shipwright.chooseBoat(
            player, "Bottle which boat?",
            { it.state == BoatState.DOCKED && it.dockPort == site.name && it.hold.used == 0 && it.hold.cannonballs == 0 }
        ) { boat ->
            if (Voyages.of(player)?.boat === boat) return@chooseBoat player.sendMessage("Disembark first.")
            if (player.inventory.getItem(slot)?.id != Obj.sailing_boat_bottle) return@chooseBoat
            boat.state = BoatState.BOTTLED
            boat.dockPort = ""
            BoatStore.save(boat)
            player.inventory.deleteItem(slot, Item(Obj.sailing_boat_bottle, 1))
            player.inventory.addItemDrop(Item(Obj.sailing_boat_bottle_full, 1).addMetaData(BOAT_KEY, boat.id.toString()))
            Ports.refreshBoats(site)
            player.sendMessage("The ${boat.name} shrinks into the bottle.")
        }
    }

    fun boatOf(player: Player, item: Item): Boat? {
        val id = item.getMetaDataO<String>(BOAT_KEY)?.toLongOrNull() ?: return null
        return BoatStore.ownedBy(player.username).firstOrNull { it.id == id }
    }

    fun unbottle(player: Player, slot: Int, item: Item) {
        val boat = boatOf(player, item)?.takeIf { it.state == BoatState.BOTTLED } ?: return player.sendMessage("This bottle holds no boat of yours; it is spent.")
        val site = PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS) ?: return player.sendMessage("You need to be at a port to release the boat.")
        boat.state = BoatState.DOCKED
        boat.dockPort = site.name
        boat.globalX = WorldWindow.toGlobal(site.shoreX, GeneratedWorld.windowX)
        boat.globalY = WorldWindow.toGlobal(site.shoreY, GeneratedWorld.windowY)
        BoatStore.save(boat)
        player.inventory.deleteItem(slot, Item(Obj.sailing_boat_bottle_full, 1))
        Ports.refreshBoats(site)
        player.sendMessage("The ${boat.name} unfolds in the water at port ${site.name}. The bottle is gone.")
    }

    fun check(player: Player, item: Item) {
        val boat = boatOf(player, item) ?: return player.sendMessage("The bottle holds a boat that is not yours.")
        player.sendMessage("${boat.name}: ${boat.type.name.lowercase()}, ${boat.hitpoints}/${boat.maxHitpoints} HP, ${if (boat.state == BoatState.BOTTLED) "bottled" else "no longer in the bottle"}.")
    }
}

@ServerStartupEvent
fun mapBoatBottles() {
    if (!EnvVars.generateMaps) return
    onItemClick(Obj.sailing_boat_bottle, options = arrayOf("Bottle-boat")) { e -> BoatBottles.bottle(e.player, e.slotId) }
    onItemClick(Obj.sailing_boat_bottle_full, options = arrayOf("Unbottle-boat")) { e -> BoatBottles.unbottle(e.player, e.slotId, e.item) }
    onItemClick(Obj.sailing_boat_bottle_full, options = arrayOf("Check")) { e -> BoatBottles.check(e.player, e.item) }
}
