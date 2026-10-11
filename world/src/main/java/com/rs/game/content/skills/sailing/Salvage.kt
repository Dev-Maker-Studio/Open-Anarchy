package com.rs.game.content.skills.sailing

enum class Wreck(
    val sailingLevel: Int,
    val salvageXp: Int,
    val sortingXp: Double,
    val durationSeconds: Int
) {
    SMALL(15, 10, 5.5, 60),
    FISHERMANS(26, 17, 9.0, 180),
    BARRACUDA(35, 31, 15.5, 180),
    LARGE(53, 48, 24.0, 180),
    PIRATE(64, 76, 31.5, 180),
    MERCENARY(73, 138, 63.5, 195),
    FREMENNIK(80, 162, 75.0, 220),
    MERCHANT(87, 200, 95.0, 240);

    companion object {
        fun available(sailingLevel: Int): List<Wreck> = entries.filter { it.sailingLevel <= sailingLevel }

        fun forLevelIndicator(level: Int): Wreck = entries[(level - 1).coerceIn(0, entries.size - 1)]
    }
}

object SalvageRolls {
    const val PLAYER_ROLL_TICKS = 4
    const val CREW_ROLL_TICKS = 5
    const val RANGE_TILES = 7
    private val CREW_CHANCE = doubleArrayOf(0.0, 0.10, 0.20, 0.30, 0.40)
    private val CREW_XP = doubleArrayOf(0.0, 0.10, 0.20, 0.30, 0.40)

    fun crewChance(deckhandiness: Int): Double = CREW_CHANCE[deckhandiness.coerceIn(0, CREW_CHANCE.size - 1)]

    fun crewXpFraction(deckhandiness: Int): Double = CREW_XP[deckhandiness.coerceIn(0, CREW_XP.size - 1)]
}
