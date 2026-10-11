package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.world.npcs.merchant.MerchantNPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import java.util.Random

private const val EPOCH_MILLIS = 1_800_000L
private const val CANDIDATES = 3
private const val MERCHANT_RADIUS = 24
private const val FEE_WAGES = 10
private const val WAGE_PER_POINT = 30
private const val MAX_STAT = 4
private const val NAME_MIX = 31L
private val FIRST_NAMES = listOf("Brine", "Salty", "Marlow", "Tamsin", "Jory", "Pell", "Wick", "Nessa", "Corin", "Dunn", "Ifor", "Merrin")
private val LAST_NAMES = listOf("Tarwick", "Hallow", "Sprat", "Penrose", "Gannet", "Rook", "Skerry", "Tidewell")

object PortMaster {
    fun talk(player: Player, npc: PortStaffNPC) {
        val site = PortSites.byName(npc.port) ?: return
        player.startConversation {
            npc(npc.id, HeadE.CALM_TALK, "Port master of ${site.name} at your service. Crews, cargo and contracts are my trade.")
            options("Port master") {
                opExec("Courier and bounty tasks") { Courier.open(player, site) }
                opExec("Claim a finished bounty") { BountyTasks.claim(player, site) }
                opExec("Crew") { crewMenu(player, site) }
                opExec("Unload the hold") { Shipwright.unload(player, site) }
                opExec("Barracuda Trials") { Barracuda.menu(player, site) }
            }
        }
    }

    fun candidates(site: PortSite): List<Crewmate> {
        val epoch = System.currentTimeMillis() / EPOCH_MILLIS
        val random = Random(site.name.hashCode() * NAME_MIX + epoch)
        return List(CANDIDATES) {
            val helm = 1 + random.nextInt(MAX_STAT)
            val deck = 1 + random.nextInt(MAX_STAT)
            val priv = 1 + random.nextInt(MAX_STAT)
            Crewmate("${FIRST_NAMES[random.nextInt(FIRST_NAMES.size)]} ${LAST_NAMES[random.nextInt(LAST_NAMES.size)]}", helm, deck, priv, (helm + deck + priv) * WAGE_PER_POINT, CrewRules.firstLevelForStats(helm + deck + priv))
        }
    }

    private fun crewMenu(player: Player, site: PortSite) {
        player.sendOptionsDialogue("Crew") {
            opExec("Hire a crewmate") { hireMenu(player, site) }
            opExec("Hire a merchant as crew") { hireMerchant(player, site) }
            opExec("Manage my crew") { manage(player, site) }
        }
    }

    private fun hireMenu(player: Player, site: PortSite) {
        Shipwright.chooseBoat(player, "Crew which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            if (boat.crew.size >= CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type)) {
                return@chooseBoat player.sendMessage("The ${boat.name} has no free crew slot at your Sailing level (${CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type)} of ${boat.type.crewSlots}).")
            }
            player.sendOptionsDialogue("Hire for the ${boat.name}") {
                candidates(site).forEach { crewmate ->
                    opExec("${crewmate.name}: H${crewmate.helmsmanship} D${crewmate.deckhandiness} P${crewmate.privateering}, ${crewmate.wage} gp") { hire(player, boat, crewmate) }
                }
            }
        }
    }

    fun hire(player: Player, boat: Boat, crewmate: Crewmate) {
        val fee = crewmate.wage * FEE_WAGES
        when {
            player.skills.getLevelForXp(Constants.SAILING) < crewmate.sailingLevel -> player.sendMessage("${crewmate.name} will only sign with a captain of Sailing level ${crewmate.sailingLevel}.")
            boat.crew.size >= CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type) -> player.sendMessage("No free crew slot.")
            !player.inventory.hasCoins(fee) -> player.sendMessage("The signing fee is $fee coins.")
            else -> {
                player.inventory.removeCoins(fee)
                boat.crew.add(Crewmate(crewmate.name, crewmate.helmsmanship, crewmate.deckhandiness, crewmate.privateering, crewmate.wage, crewmate.sailingLevel))
                BoatStore.save(boat)
                player.sendMessage("${crewmate.name} joins the ${boat.name} (wage ${crewmate.wage} gp every 15 minutes at sea). Assign a job under Manage my crew.")
            }
        }
    }

    private fun hireMerchant(player: Player, site: PortSite) {
        val merchant = player.queryNearbyNPCsByTileRange(MERCHANT_RADIUS) { it is MerchantNPC && !it.isDead && !it.hasFinished() }.firstOrNull() as? MerchantNPC
            ?: return player.sendMessage("There is no merchant nearby to sign on.")
        Shipwright.chooseBoat(player, "Crew which boat?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            val crewmate = Crewmate.merchant(merchant.name, merchant.level)
            if (boat.crew.size >= CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type)) return@chooseBoat player.sendMessage("The ${boat.name} has no free crew slot.")
            if (player.skills.getLevelForXp(Constants.SAILING) < crewmate.sailingLevel) return@chooseBoat player.sendMessage("A merchant will only sail for a captain of Sailing level ${crewmate.sailingLevel}.")
            val fee = crewmate.wage * FEE_WAGES
            if (!player.inventory.hasCoins(fee)) return@chooseBoat player.sendMessage("The merchant asks $fee coins to sign on (wage ${crewmate.wage}).")
            player.inventory.removeCoins(fee)
            boat.crew.add(crewmate)
            BoatStore.save(boat)
            merchant.finish()
            player.sendMessage("${crewmate.name} the merchant signs on to the ${boat.name} as a one-off hire.")
        }
    }

    private fun manage(player: Player, site: PortSite) {
        Shipwright.chooseBoat(player, "Manage which crew?", { it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            SailingScreens.openCrew(player, boat, site)
        }
    }

    fun assign(player: Player, boat: Boat, crewmate: Crewmate, job: CrewJob) {
        when {
            job == CrewJob.CANNON && boat.cannon == null -> player.sendMessage("The ${boat.name} has no cannon.")
            job == CrewJob.SALVAGE && boat.hook == null -> player.sendMessage("The ${boat.name} has no salvaging hook.")
            job == CrewJob.TRAWL && boat.nets.isEmpty() -> player.sendMessage("The ${boat.name} has no trawling net.")
            else -> {
                crewmate.job = job
                BoatStore.save(boat)
                player.sendMessage("${crewmate.name} is now ${job.name.lowercase().replace('_', ' ')}.")
            }
        }
    }
}
