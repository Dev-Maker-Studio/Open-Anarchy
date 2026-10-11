package org.darkan.world

import com.rs.game.content.skills.sailing.Boat
import com.rs.game.content.skills.sailing.BoatState
import com.rs.game.content.skills.sailing.BoatType
import com.rs.game.content.skills.sailing.CargoHold
import com.rs.game.content.skills.sailing.Compass
import com.rs.game.content.skills.sailing.HullTier
import com.rs.game.content.skills.sailing.Materials
import com.rs.game.content.skills.sailing.sectorCoordinates
import com.rs.game.content.skills.sailing.sectorName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SailingWorldTest {
    @Test
    fun sectorNamesRoundTrip() {
        assertEquals("ZA", sectorName(0, 0))
        assertEquals("AZ", sectorName(25, 25))
        assertEquals(0 to 0, sectorCoordinates("ZA"))
        assertNull(sectorCoordinates("A1"))
    }

    @Test
    fun holdAcceptsOnlyWhatFits() {
        val hold = CargoHold(4)
        assertEquals(3, hold.addCrates(1L, 3))
        assertEquals(1, hold.addGood(995, 5))
        assertEquals(0, hold.free)
        hold.clearLost()
        assertEquals(4, hold.free)
    }

    @Test
    fun capsizeEmptiesTheHoldAndNeedsRecovery() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        boat.hold.addCrates(7L, 2)
        boat.capsize()
        assertTrue(boat.capsized)
        assertEquals(BoatState.CAPSIZED, boat.state)
        assertEquals(0, boat.hold.used)
        boat.recover()
        assertFalse(boat.capsized)
        assertEquals(boat.maxHitpoints, boat.hitpoints)
    }

    @Test
    fun hullUpgradeGrowsTheHold() {
        val boat = Boat(1, "owner", BoatType.RAFT, "Test")
        val before = boat.hold.capacity
        boat.upgradeHull(HullTier.OAK)
        assertTrue(boat.hold.capacity > before)
    }

    @Test
    fun compassParsesWordsAndDegrees() {
        assertEquals(45, Compass.parse("ne"))
        assertEquals(270, Compass.parse("West"))
        assertEquals(10, Compass.parse("370"))
        assertNull(Compass.parse("sideways"))
    }

    @Test
    fun costsScaleWithBoatSize() {
        val raft = Materials.hullCost(HullTier.WOODEN.wood, BoatType.RAFT).items.values.sum()
        val sloop = Materials.hullCost(HullTier.WOODEN.wood, BoatType.SLOOP).items.values.sum()
        assertTrue(sloop > raft)
        assertNotNull(Materials.bypassFee(0))
        assertNull(Materials.bypassFee(6))
    }
}
