package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils

object Stations {
    const val EXTRACTOR_TICKS = 109
    private const val EXTRACTOR_XP = 250.0
    private const val CREW_REPAIR_TICKS = 10
    private const val TICK_SECONDS_NUM = 6
    private const val TICK_SECONDS_DEN = 10

    fun harvest(player: Player, voyage: Voyage) {
        if (voyage.ticks < voyage.extractorReadyAt) {
            val seconds = ((voyage.extractorReadyAt - voyage.ticks) * TICK_SECONDS_NUM + TICK_SECONDS_DEN - 1) / TICK_SECONDS_DEN
            return player.sendMessage("The crystal extractor is still charging ($seconds seconds).")
        }
        voyage.extractorReadyAt = voyage.ticks + EXTRACTOR_TICKS
        SailingXp.grant(player, voyage.boat, EXTRACTOR_XP)
        val stored = voyage.trim.addMote(true)
        player.sendMessage(if (stored) "You harvest a crystal mote into the catcher." else "You harvest a crystal mote from the extractor.")
    }

    fun sort(player: Player, voyage: Voyage) {
        val boat = voyage.boat
        val xp = boat.hold.sortingXp
        if (xp <= 0.0) return player.sendMessage("There is no unsorted salvage in the hold.")
        boat.hold.sortingXp = 0.0
        SailingXp.grant(player, boat, xp)
        BoatStore.save(boat)
        player.sendMessage("You sort the salvage in the hold.")
    }

    fun tickCrewRepair(voyage: Voyage, player: Player) {
        val boat = voyage.boat
        if (voyage.ticks % CREW_REPAIR_TICKS != 0L || boat.hitpoints >= boat.maxHitpoints || voyage.sickTicks > 0) return
        voyage.crewWith(CrewJob.REPAIR).forEach { crewmate ->
            val bonus = Utils.random(crewmate.effectiveDeckhandiness(boat).coerceAtLeast(1))
            val healed = RepairKits.useOne(boat, bonus)
            if (healed > 0) player.sendMessage("${crewmate.name} patches the hull for $healed hit points (${boat.hitpoints}/${boat.maxHitpoints}).")
        }
    }
}
