package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.engine.dialogue.startConversation
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow

private const val REPAIR_FEE_PER_HP = 50
private const val CANNONBALL_PRICE = 4
private const val CANNONBALL_PACK = 50
private const val CONSTRUCTION_XP_PER_LEVEL = 25
private const val CONSTRUCTION_XP_BASE = 100
private const val MAX_OPTIONS = 5
private val NAME_DESCRIPTORS = listOf("Salty", "Gallant", "Drifting", "Merry", "Stormy", "Lucky", "Brave", "Gentle")
private val NAME_NOUNS = listOf("Gull", "Barnacle", "Kraken", "Mermaid", "Anchor", "Dolphin", "Petrel", "Lantern")

private class Upgrade(
    val label: String,
    val sailingLevel: Int,
    val constructionLevel: Int,
    val cost: Materials.Cost,
    val bypassTier: Int,
    val apply: (Boat) -> Unit
)

object Shipwright {
    fun talk(player: Player, npc: PortStaffNPC) {
        val site = PortSites.byName(npc.port) ?: return
        player.startConversation {
            npc(npc.id, HeadE.CHEERFUL, "Welcome to the yard, captain. Boats built, mended and recovered, at fair prices.")
            options("Shipwright of port ${site.name}") {
                opExec("Buy a boat") { buyMenu(player, site) }
                opExec("Upgrade hull, helm, sails or keel") { partsMenu(player, site) }
                opExec("Fit facilities") { facilityMenu(player, site) }
                opExec("Repair or recover a boat") { repairMenu(player, site) }
                opExec("More") { moreMenu(player, site) }
            }
        }
    }

    fun chooseBoat(player: Player, title: String, accepts: (Boat) -> Boolean, action: (Boat) -> Unit) {
        val boats = BoatStore.ownedBy(player.username).filter(accepts)
        when {
            boats.isEmpty() -> player.sendMessage("You have no boat that qualifies for that here.")
            boats.size == 1 -> action(boats.first())
            else -> player.sendOptionsDialogue(title) {
                boats.take(MAX_OPTIONS).forEach { boat -> opExec("${boat.name} (${boat.type.name.lowercase()})") { action(boat) } }
            }
        }
    }

    private fun buyMenu(player: Player, site: PortSite) {
        player.sendOptionsDialogue("Buy a boat") {
            BoatType.entries.forEach { type ->
                opExec("${type.name.lowercase().replaceFirstChar { it.uppercase() }}: ${"%,d".format(type.cost)} gp (Sailing ${type.sailingLevel})") { buy(player, site, type) }
            }
        }
    }

    private fun buy(player: Player, site: PortSite, type: BoatType) {
        val level = player.skills.getLevelForXp(Constants.SAILING)
        val owned = BoatStore.ownedBy(player.username)
        when {
            level < type.sailingLevel -> player.sendMessage("You need Sailing level ${type.sailingLevel} for a ${type.name.lowercase()}.")
            owned.size >= BoatType.maxOwned(level) -> player.sendMessage("You can own ${BoatType.maxOwned(level)} boat(s) at Sailing level $level.")
            !player.inventory.hasCoins(type.cost) -> player.sendMessage("A ${type.name.lowercase()} costs ${"%,d".format(type.cost)} coins.")
            else -> {
                player.inventory.removeCoins(type.cost)
                val boat = Boat(
                    System.nanoTime(), player.username, type, "${NAME_DESCRIPTORS.random()} ${NAME_NOUNS.random()}",
                    dockPort = site.name, globalX = WorldWindow.toGlobal(site.shoreX, GeneratedWorld.windowX), globalY = WorldWindow.toGlobal(site.shoreY, GeneratedWorld.windowY)
                )
                BoatStore.save(boat)
                Ports.refreshBoats(site)
                player.sendMessage("You buy a ${type.name.lowercase()} named ${boat.name}. It is moored at port ${site.name}; use the gangplank to board.")
            }
        }
    }

    private fun partsMenu(player: Player, site: PortSite) {
        chooseBoat(player, "Upgrade which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            player.sendOptionsDialogue("Upgrade the ${boat.name}") {
                opExec("Hull") { offer(player, site, boat, hullUpgrade(boat)) }
                opExec("Helm") { offer(player, site, boat, helmUpgrade(boat)) }
                opExec("Mast and sails") { offer(player, site, boat, sailUpgrade(boat)) }
                opExec("Keel") { offer(player, site, boat, keelUpgrade(boat)) }
            }
        }
    }

    private fun hullUpgrade(boat: Boat): Upgrade? = HullTier.entries.getOrNull(boat.hull.ordinal + 1)?.let { tier ->
        Upgrade("${tier.name.lowercase()} hull", tier.sailingLevel, tier.constructionLevel, Materials.hullCost(tier.wood, boat.type), tier.wood.ordinal) { it.upgradeHull(tier) }
    }

    private fun helmUpgrade(boat: Boat): Upgrade? = HelmTier.entries.getOrNull(boat.helm.ordinal + 1)?.let { tier ->
        Upgrade("${tier.name.lowercase()} helm", tier.sailingLevel, tier.constructionLevel, Materials.helmCost(tier, boat.type), tier.ordinal) { it.helm = tier }
    }

    private fun sailUpgrade(boat: Boat): Upgrade? = SailTier.entries.getOrNull(boat.sail.ordinal + 1)?.let { tier ->
        Upgrade("${tier.name.lowercase()} mast and sails", tier.sailingLevel, tier.constructionLevel, Materials.sailCost(Wood.entries[tier.ordinal], boat.type), tier.ordinal) { it.sail = tier }
    }

    private fun keelUpgrade(boat: Boat): Upgrade? {
        if (!boat.type.hasKeel) return null
        val tier = KeelTier.entries.getOrNull((boat.keel?.ordinal ?: -1) + 1) ?: return null
        return Upgrade("${tier.name.lowercase()} keel", tier.sailingLevel, tier.constructionLevel, Materials.keelCost(tier, boat.type), tier.ordinal) { it.upgradeKeel(tier) }
    }

    private fun offer(player: Player, site: PortSite, boat: Boat, upgrade: Upgrade?) {
        if (upgrade == null) return player.sendMessage("The ${boat.name} cannot be upgraded further there.")
        val fee = Materials.bypassFee(upgrade.bypassTier)
        player.sendMessage("${upgrade.label.replaceFirstChar { it.uppercase() }}: Sailing ${upgrade.sailingLevel}, Construction ${upgrade.constructionLevel}; needs ${upgrade.cost.describe(::itemName)}. Hull and keel upgrades restore the boat to full health.")
        player.sendOptionsDialogue("Build the ${upgrade.label}?") {
            opExec("Build it myself (hammer and saw)") { build(player, boat, upgrade, 0, false) }
            if (fee != null) opExec("Pay the Shipwright ${"%,d".format(fee)} gp to build it") { build(player, boat, upgrade, fee, true) }
            opExec("Not now") {}
        }
    }

    private fun build(player: Player, boat: Boat, upgrade: Upgrade, fee: Int, shipwrightBuilds: Boolean) {
        if (boat.state != BoatState.DOCKED) return player.sendMessage("The boat has to be moored.")
        if (player.skills.getLevelForXp(Constants.SAILING) < upgrade.sailingLevel) return player.sendMessage("You need Sailing level ${upgrade.sailingLevel}.")
        if (!shipwrightBuilds) {
            if (player.skills.getLevelForXp(Constants.CONSTRUCTION) < upgrade.constructionLevel) return player.sendMessage("You need Construction level ${upgrade.constructionLevel}, or pay the Shipwright to build it.")
            if (!player.inventory.containsItem(Obj.hammer) || !player.inventory.containsItem(Obj.poh_saw)) return player.sendMessage("You need a hammer and a saw.")
        } else if (!player.inventory.hasCoins(fee)) {
            return player.sendMessage("The Shipwright wants ${"%,d".format(fee)} coins.")
        }
        if (!upgrade.cost.affordableBy { player.inventory.getAmountOf(it) }) return player.sendMessage("You need ${upgrade.cost.describe(::itemName)}.")
        upgrade.cost.items.forEach { (id, amount) -> player.inventory.deleteItem(id, amount) }
        if (shipwrightBuilds) player.inventory.removeCoins(fee)
        upgrade.apply(boat)
        BoatStore.save(boat)
        if (!shipwrightBuilds) player.skills.addXp(Constants.CONSTRUCTION, (upgrade.constructionLevel * CONSTRUCTION_XP_PER_LEVEL + CONSTRUCTION_XP_BASE).toDouble())
        player.sendMessage("The ${boat.name} is fitted with a ${upgrade.label}.")
    }

    private fun facilityMenu(player: Player, site: PortSite) {
        chooseBoat(player, "Fit which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            player.sendOptionsDialogue("Facilities for the ${boat.name} (${boat.hotspotsUsed}/${boat.type.hotspots} hotspots)") {
                opExec("Salvaging hook") { offer(player, site, boat, hookUpgrade(boat)) }
                opExec("Cannon") { offer(player, site, boat, cannonUpgrade(boat)) }
                opExec("Anchor or inoculation station") { simpleFacilities(player, site, boat, Facility.ANCHOR, Facility.INOCULATION_STATION) }
                opExec("Wind catcher or eternal brazier") { simpleFacilities(player, site, boat, Facility.WIND_CATCHER, Facility.BRAZIER) }
            }
        }
    }

    private fun simpleFacilities(player: Player, site: PortSite, boat: Boat, first: Facility, second: Facility) {
        player.sendOptionsDialogue("Which facility?") {
            opExec(first.label.replaceFirstChar { it.uppercase() }) { offer(player, site, boat, facilityUpgrade(boat, first)) }
            opExec(second.label.replaceFirstChar { it.uppercase() }) { offer(player, site, boat, facilityUpgrade(boat, second)) }
        }
    }

    private fun hookUpgrade(boat: Boat): Upgrade? {
        val tier = HookTier.entries.getOrNull((boat.hook?.ordinal ?: -1) + 1) ?: return null
        if (boat.hook == null && boat.hotspotsUsed >= boat.type.hotspots) return null
        return Upgrade("${tier.label} salvaging hook", tier.sailingLevel, tier.constructionLevel, Materials.hookCost(tier), tier.wood.ordinal) { it.hook = tier }
    }

    private fun cannonUpgrade(boat: Boat): Upgrade? {
        val tier = CannonTier.entries.getOrNull((boat.cannon?.ordinal ?: -1) + 1) ?: return null
        if (boat.cannon == null && boat.hotspotsUsed >= boat.type.hotspots) return null
        return Upgrade("${tier.label} cannon", tier.sailingLevel, tier.constructionLevel, Materials.cannonCost(tier), tier.wood.ordinal) { it.cannon = tier }
    }

    private fun facilityUpgrade(boat: Boat, facility: Facility): Upgrade? {
        if (facility in boat.facilities || boat.hotspotsUsed >= boat.type.hotspots) return null
        return Upgrade(facility.label, facility.sailingLevel, facility.constructionLevel, Materials.facilityCost(facility), facility.wood.ordinal) {
            it.facilities.add(facility)
            if (facility == Facility.BRAZIER) it.hasBrazier = true
            if (facility == Facility.INOCULATION_STATION) it.hasInoculationStation = true
        }
    }

    private fun repairMenu(player: Player, site: PortSite) {
        player.sendOptionsDialogue("Repair or recover") {
            opExec("Repair a boat (${REPAIR_FEE_PER_HP} gp per missing HP)") { repair(player, site) }
            opExec("Recover a capsized boat") { recover(player, site) }
            opExec("Move a boat here") { relocate(player, site) }
        }
    }

    private fun repair(player: Player, site: PortSite) {
        chooseBoat(player, "Repair which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name && it.hitpoints < it.maxHitpoints }) { boat ->
            val fee = (boat.maxHitpoints - boat.hitpoints) * REPAIR_FEE_PER_HP
            if (!player.inventory.hasCoins(fee)) return@chooseBoat player.sendMessage("The Shipwright wants $fee coins.")
            player.inventory.removeCoins(fee)
            boat.repairFully()
            BoatStore.save(boat)
            player.sendMessage("The Shipwright repairs the ${boat.name} for $fee coins.")
        }
    }

    private fun recover(player: Player, site: PortSite) {
        chooseBoat(player, "Recover which boat?", { it.state == BoatState.CAPSIZED }) { boat ->
            val fee = boat.type.recoveryFee
            if (!player.inventory.hasCoins(fee)) return@chooseBoat player.sendMessage("The recovery fee is ${"%,d".format(fee)} coins.")
            player.inventory.removeCoins(fee)
            boat.recover()
            moor(boat, site)
            player.sendMessage("A port wizard hauls the ${boat.name} back to port ${site.name} for ${"%,d".format(fee)} coins.")
        }
    }

    private fun relocate(player: Player, site: PortSite) {
        val sailing = Voyages.of(player)
        chooseBoat(player, "Move which boat?", { it.state != BoatState.CAPSIZED && it !== sailing?.boat && !(it.state == BoatState.DOCKED && it.dockPort == site.name) }) { boat ->
            val fee = boat.type.recoveryFee
            if (!player.inventory.hasCoins(fee)) return@chooseBoat player.sendMessage("The fee is ${"%,d".format(fee)} coins; cargo in the hold is lost.")
            player.inventory.removeCoins(fee)
            val lost = SailorStore.of(player.username).tasks.removeIf { it.collected && boat.hold.crates.containsKey(it.id) }
            boat.hold.clearLost()
            val previous = boat.dockPort
            boat.state = BoatState.DOCKED
            moor(boat, site)
            PortSites.byName(previous)?.let { Ports.refreshBoats(it) }
            if (lost) SailorStore.save(SailorStore.of(player.username))
            player.sendMessage("The ${boat.name} is moved to port ${site.name}. Its cargo is lost.")
        }
    }

    private fun moor(boat: Boat, site: PortSite) {
        boat.dockPort = site.name
        boat.globalX = WorldWindow.toGlobal(site.shoreX, GeneratedWorld.windowX)
        boat.globalY = WorldWindow.toGlobal(site.shoreY, GeneratedWorld.windowY)
        BoatStore.save(boat)
        Ports.refreshBoats(site)
    }

    private fun moreMenu(player: Player, site: PortSite) {
        player.sendOptionsDialogue("More") {
            opExec("Buy $CANNONBALL_PACK cannonballs (${CANNONBALL_PACK * CANNONBALL_PRICE} gp)") { buyBalls(player, site) }
            opExec("Unload the hold") { unload(player, site) }
            opExec("Destroy a boat") { destroy(player, site) }
        }
    }

    private fun buyBalls(player: Player, site: PortSite) {
        chooseBoat(player, "Load which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            val cost = CANNONBALL_PACK * CANNONBALL_PRICE
            if (!player.inventory.hasCoins(cost)) return@chooseBoat player.sendMessage("That costs $cost coins.")
            player.inventory.removeCoins(cost)
            boat.hold.cannonballs += CANNONBALL_PACK
            BoatStore.save(boat)
            player.sendMessage("$CANNONBALL_PACK cannonballs are stowed in the ${boat.name}'s hold (${boat.hold.cannonballs} in all).")
        }
    }

    fun unload(player: Player, site: PortSite) {
        chooseBoat(player, "Unload which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name && it.hold.goods.isNotEmpty() }) { boat ->
            boat.hold.goods.forEach { (item, amount) -> player.inventory.addItemDrop(item, amount) }
            val sorted = boat.hold.sortingXp
            boat.hold.goods.clear()
            boat.hold.sortingXp = 0.0
            BoatStore.save(boat)
            if (sorted > 0.0) player.skills.addXp(Constants.SAILING, sorted)
            player.sendMessage("You sort and unload the salvage from the ${boat.name}. Merchants buy it at the cache price.")
        }
    }

    private fun destroy(player: Player, site: PortSite) {
        chooseBoat(player, "Destroy which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            player.sendOptionsDialogue("Destroy the ${boat.name} for good?") {
                opExec("Yes, destroy it") {
                    val profile = SailorStore.of(player.username)
                    profile.tasks.removeIf { boat.hold.crates.containsKey(it.id) }
                    SailorStore.save(profile)
                    BoatStore.delete(boat)
                    Ports.refreshBoats(site)
                    player.sendMessage("The ${boat.name} is broken up. Nothing is returned.")
                }
                opExec("No") {}
            }
        }
    }

    private fun itemName(id: Int): String = ItemDefinitions.getDefs(id).name.lowercase()
}
