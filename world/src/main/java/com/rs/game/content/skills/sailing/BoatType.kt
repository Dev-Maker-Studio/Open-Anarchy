package com.rs.game.content.skills.sailing

import org.darkan.core.gameval.gen.Npc

enum class BoatType(
    val sailingLevel: Int,
    val width: Int,
    val length: Int,
    val hotspots: Int,
    val players: Int,
    val crewSlots: Int,
    val cost: Int,
    val recoveryFee: Int,
    val hasKeel: Boolean,
    val holdBase: Int,
    val holdPerTier: Int,
    val visualNpc: Int,
    val visualSize: Int
) {
    RAFT(1, 1, 3, 1, 2, 0, 1_000, 250, false, 4, 1, Npc.acs_battleships_emptyship, 2),
    SKIFF(15, 2, 5, 7, 5, 2, 15_000, 4_125, true, 12, 2, Npc.sang_boat, 4),
    SLOOP(50, 3, 10, 11, 10, 5, 200_000, 50_000, true, 30, 4, Npc._100_jubbly_ogre_boat, 5);

    companion object {
        private val OWNED_AT_LEVEL = listOf(1 to 1, 15 to 2, 50 to 3, 78 to 4, 91 to 5)

        fun maxOwned(sailingLevel: Int): Int = OWNED_AT_LEVEL.lastOrNull { sailingLevel >= it.first }?.second ?: 0
    }
}
