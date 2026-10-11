package org.darkan.world

import com.rs.game.content.skills.sailing.Boat
import com.rs.game.content.skills.sailing.BoatType
import com.rs.game.content.skills.sailing.HullTier
import com.rs.game.content.skills.sailing.KeelTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoatStatsTest {
    @Test
    fun skiffHitpointsAreHullPlusKeel() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        assertEquals(30 + 50, boat.maxHitpoints)
        assertEquals(boat.maxHitpoints, boat.hitpoints)
    }

    @Test
    fun raftHasNoKeel() {
        val boat = Boat(1, "owner", BoatType.RAFT, "Test")
        boat.upgradeKeel(KeelTier.DRAGON)
        assertEquals(20, boat.maxHitpoints)
    }

    @Test
    fun upgradingTheHullRestoresHitpoints() {
        val boat = Boat(1, "owner", BoatType.SLOOP, "Test")
        boat.damage(30)
        boat.upgradeHull(HullTier.OAK)
        assertEquals(boat.maxHitpoints, boat.hitpoints)
    }

    @Test
    fun flatArmourReducesEveryHit() {
        val boat = Boat(1, "owner", BoatType.SLOOP, "Test", keel = KeelTier.RUNE)
        assertEquals(2, boat.damage(10))
        assertFalse(boat.capsized)
        repeat(100) { boat.damage(10) }
        assertTrue(boat.capsized)
    }

    @Test
    fun boatsOwnedGrowWithLevel() {
        assertEquals(1, BoatType.maxOwned(1))
        assertEquals(2, BoatType.maxOwned(15))
        assertEquals(5, BoatType.maxOwned(120))
    }
}
