package com.rs.game.content.skills.sailing

enum class CrewJob { HELM, SALVAGE, CANNON, NONE }

class Crewmate(
    val name: String,
    val helmsmanship: Int,
    val deckhandiness: Int,
    val privateering: Int,
    val wage: Int,
    val sailingLevel: Int,
    var job: CrewJob = CrewJob.NONE
) {
    companion object {
        private const val MERCHANT_WAGE_PER_LEVEL = 4
        private const val MERCHANT_STAT_DIVISOR = 25
        private const val MAX_STAT = 4
        private const val MERCHANT_SAILING_LEVEL = 40

        fun merchant(name: String, combatLevel: Int): Crewmate {
            val stat = (combatLevel / MERCHANT_STAT_DIVISOR + 1).coerceIn(1, MAX_STAT)
            return Crewmate(name, stat, stat, stat, combatLevel * MERCHANT_WAGE_PER_LEVEL, MERCHANT_SAILING_LEVEL)
        }
    }
}

object CrewRules {
    private val SLOTS_AT_LEVEL = listOf(40 to 1, 50 to 2, 60 to 3, 75 to 4, 90 to 5)

    fun unlockedSlots(sailingLevel: Int, type: BoatType): Int =
        (SLOTS_AT_LEVEL.lastOrNull { sailingLevel >= it.first }?.second ?: 0).coerceAtMost(type.crewSlots)

    fun cannonMaxHit(sailingLevel: Int, cannonStrength: Int, ballStrength: Int, privateering: Int): Int {
        val base = (sailingLevel * (cannonStrength + ballStrength + BASE_STRENGTH) + ROUNDING) / DIVISOR
        return base * (privateering + PRIVATEERING_OFFSET) / PRIVATEERING_DIVISOR
    }

    private const val POINTS_PER_LEVEL_STEP = 2
    private const val LEVEL_STEP = 10

    fun firstLevelForStats(statPoints: Int): Int = ((statPoints - POINTS_PER_LEVEL_STEP) * LEVEL_STEP).coerceAtLeast(1).coerceAtMost(MERCHANT_CAP_LEVEL)

    private const val MERCHANT_CAP_LEVEL = 90
    private const val BASE_STRENGTH = 64
    private const val ROUNDING = 320
    private const val DIVISOR = 640
    private const val PRIVATEERING_OFFSET = 13
    private const val PRIVATEERING_DIVISOR = 20
}
