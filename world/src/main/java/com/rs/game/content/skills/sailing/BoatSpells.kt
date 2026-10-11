package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Obj

object BoatSpells {
    const val SUMMON_LEVEL = 56
    const val TELEPORT_LEVEL = 67
    private const val SUMMON_LAW = 1
    private const val SUMMON_WATER = 3
    private const val TELEPORT_LAW = 2
    private const val TELEPORT_WATER = 4
    private const val SUMMON_XP = 60.0
    private const val TELEPORT_XP = 80.0

    fun menu(player: Player) {
        player.sendOptionsDialogue("Boat spells") {
            opExec("Summon Boat (Magic $SUMMON_LEVEL)") { summon(player) }
            opExec("Teleport to Boat (Magic $TELEPORT_LEVEL)") { teleportToBoat(player) }
        }
    }

    private fun hasRunes(player: Player, law: Int, water: Int): Boolean =
        player.inventory.getAmountOf(Obj.lawrune) >= law && player.inventory.getAmountOf(Obj.waterrune) >= water

    private fun spend(player: Player, law: Int, water: Int) {
        player.inventory.deleteItem(Obj.lawrune, law)
        player.inventory.deleteItem(Obj.waterrune, water)
    }

    fun summon(player: Player) {
        if (player.skills.getLevel(Constants.MAGIC) < SUMMON_LEVEL) return player.sendMessage("You need Magic level $SUMMON_LEVEL to summon a boat.")
        val site = PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS) ?: return player.sendMessage("You can only summon a boat to a mooring point.")
        if (!hasRunes(player, SUMMON_LAW, SUMMON_WATER)) return player.sendMessage("Summon Boat needs $SUMMON_LAW law rune and $SUMMON_WATER water runes.")
        Shipwright.chooseBoat(
            player, "Summon which boat?",
            { it.state == BoatState.DOCKED && it.dockPort != site.name && it.dockPort.isNotEmpty() && (Facility.TELEPORT_FOCUS in it.facilities || Facility.GREATER_TELEPORT_FOCUS in it.facilities) }
        ) { boat ->
            spend(player, SUMMON_LAW, SUMMON_WATER)
            Shipwright.moveBoat(player, boat, site)
            player.skills.addXp(Constants.MAGIC, SUMMON_XP)
            player.sendMessage("The ${boat.name} glides in to port ${site.name}. Cargo, crates and salvage in the hold are lost.")
        }
    }

    fun teleportToBoat(player: Player) {
        if (player.skills.getLevel(Constants.MAGIC) < TELEPORT_LEVEL) return player.sendMessage("You need Magic level $TELEPORT_LEVEL to teleport to a boat.")
        if (!hasRunes(player, TELEPORT_LAW, TELEPORT_WATER)) return player.sendMessage("Teleport to Boat needs $TELEPORT_LAW law runes and $TELEPORT_WATER water runes.")
        Shipwright.chooseBoat(player, "Teleport to which boat?", { it.state == BoatState.DOCKED && Facility.GREATER_TELEPORT_FOCUS in it.facilities && PortSites.byName(it.dockPort) != null }) { boat ->
            val site = PortSites.byName(boat.dockPort) ?: return@chooseBoat
            spend(player, TELEPORT_LAW, TELEPORT_WATER)
            player.tele(Ports.landingTile(site))
            player.skills.addXp(Constants.MAGIC, TELEPORT_XP)
            player.sendMessage("You teleport to the ${boat.name} at port ${site.name}.")
        }
    }
}
