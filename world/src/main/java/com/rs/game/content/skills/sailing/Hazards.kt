package com.rs.game.content.skills.sailing

import java.util.Random

enum class Hazard(val minThreat: Int, val damage: Int) {
    REEF(1, 4),
    RAPIDS(3, 6),
    KELP(5, 0),
    STORM(8, 10),
    ICE(15, 8),
    CRYSTAL(20, 0),
    SICKNESS(10, 0);

    fun protectedBy(boat: Boat): Boolean = when (this) {
        REEF -> false
        RAPIDS -> boat.helm.rapids >= RapidsClass.GENTLE
        KELP -> boat.helm >= HelmTier.ADAMANT
        STORM -> boat.sail.storm == StormResistance.FULL
        ICE -> boat.hasBrazier
        CRYSTAL -> boat.keel != null && boat.keel!! >= KeelTier.ADAMANT && boat.helm >= HelmTier.ADAMANT
        SICKNESS -> boat.hasInoculationStation
    }

    fun damageTo(boat: Boat): Int = if (protectedBy(boat)) 0 else damage
}

object HazardField {
    private const val CELL = 24
    private const val DENSITY_PERCENT = 6
    private const val MIX = 0x9E3779B97F4A7C15uL

    fun at(seed: Long, x: Int, y: Int, threat: Int): Hazard? {
        val cellX = Math.floorDiv(x, CELL)
        val cellY = Math.floorDiv(y, CELL)
        val random = Random(seed xor (cellX * MIX.toLong()) xor (cellY.toLong() shl 20))
        if (random.nextInt(100) >= DENSITY_PERCENT) return null
        val allowed = Hazard.entries.filter { it.minThreat <= threat }
        return allowed[random.nextInt(allowed.size)]
    }
}
