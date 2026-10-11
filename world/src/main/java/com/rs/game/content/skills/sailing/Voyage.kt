package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.npc.NPC
import java.util.concurrent.ConcurrentHashMap

class SalvageJob(val site: WreckSite, var remaining: Int)

class Voyage(
    val username: String,
    val boat: Boat,
    val movement: BoatMovement,
    val trim: SailTrim,
    val visual: NPC
) {
    var ticks = 0L
    var salvage: SalvageJob? = null
    var cannonAuto = false
    var sickTicks = 0
    var crewTrimCountdown = 0
    var nextCannonTick = 0L
    var nextCrewCannonTick = 0L
    var lastMessageTick = 0L
    var persistedTick = 0L
    val monsters: MutableSet<SeaMonster> = ConcurrentHashMap.newKeySet()

    val tileX: Int get() = movement.x.toInt()

    val tileY: Int get() = movement.y.toInt()

    fun crewWith(job: CrewJob): List<Crewmate> = boat.crew.filter { it.job == job }
}

enum class Compass(val degrees: Int, vararg val words: String) {
    NORTH(0, "n", "north"),
    NORTH_EAST(45, "ne", "northeast"),
    EAST(90, "e", "east"),
    SOUTH_EAST(135, "se", "southeast"),
    SOUTH(180, "s", "south"),
    SOUTH_WEST(225, "sw", "southwest"),
    WEST(270, "w", "west"),
    NORTH_WEST(315, "nw", "northwest");

    companion object {
        fun parse(word: String): Int? = entries.firstOrNull { word.lowercase() in it.words }?.degrees ?: word.toIntOrNull()?.let { Math.floorMod(it, FULL_TURN) }

        fun nameOf(degrees: Int): String = entries.minBy { kotlin.math.min(Math.floorMod(it.degrees - degrees, FULL_TURN), Math.floorMod(degrees - it.degrees, FULL_TURN)) }.words.last()

        private const val FULL_TURN = 360
    }
}
