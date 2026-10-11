package org.darkan.world

import com.rs.game.content.skills.sailing.BagTier
import com.rs.game.content.skills.sailing.Boat
import com.rs.game.content.skills.sailing.BoatMovement
import com.rs.game.content.skills.sailing.BoatStore
import com.rs.game.content.skills.sailing.BoatType
import com.rs.game.content.skills.sailing.CaptainsLog
import com.rs.game.content.skills.sailing.ChartKind
import com.rs.game.content.skills.sailing.ChartPoint
import com.rs.game.content.skills.sailing.CrewRules
import com.rs.game.content.skills.sailing.HazardField
import com.rs.game.content.skills.sailing.PortTasks
import com.rs.game.content.skills.sailing.SailTrim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SailingLogicTest {
    @Test
    fun boatMovesAlongHeadingOnWaterAndStopsAtShore() {
        val boat = Boat(1, "owner", BoatType.RAFT, "Test")
        val movement = BoatMovement(boat, 100.0, 100.0, 0)
        movement.sailsSet = true
        repeat(20) { movement.tick { _, y -> y < 110 } }
        assertTrue(movement.y > 100.0)
        assertTrue(movement.y < 110.0)
        assertEquals(0.0, movement.speed)
    }

    @Test
    fun anchoredBoatDoesNotMove() {
        val boat = Boat(1, "owner", BoatType.RAFT, "Test")
        val movement = BoatMovement(boat, 100.0, 100.0, 90)
        movement.sailsSet = true
        movement.anchored = true
        repeat(10) { movement.tick { _, _ -> true } }
        assertEquals(100.0, movement.x)
    }

    @Test
    fun trimGivesXpAndRequiresAnOpenGust() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        val movement = BoatMovement(boat, 0.0, 0.0)
        val trim = SailTrim(boat, movement)
        movement.sailsSet = true
        assertEquals(0.0, trim.trim(1, true))
        repeat(60) { trim.tick() }
        assertTrue(trim.gustOpen)
        assertEquals(10.5, trim.trim(1, true))
    }

    @Test
    fun courierBagFollowsBaseXp() {
        assertEquals(BagTier.TINY, BagTier.forXp(399))
        assertEquals(BagTier.SMALL, BagTier.forXp(400))
        assertEquals(BagTier.HUGE, BagTier.forXp(9000))
        assertEquals(1, PortTasks.slots(1))
        assertEquals(5, PortTasks.slots(120))
    }

    @Test
    fun hazardsAreDeterministic() {
        assertEquals(HazardField.at(7L, 500, 900, 12), HazardField.at(7L, 500, 900, 12))
    }

    @Test
    fun chartingPaysOnce() {
        val log = CaptainsLog()
        val point = ChartPoint("sea", ChartKind.SPYGLASS, 1, 2)
        assertEquals(50, log.record(point))
        assertEquals(0, log.record(point))
    }

    @Test
    fun crewSlotsUnlockWithLevelAndBoat() {
        assertEquals(0, CrewRules.unlockedSlots(39, BoatType.SKIFF))
        assertEquals(1, CrewRules.unlockedSlots(40, BoatType.SKIFF))
        assertEquals(2, CrewRules.unlockedSlots(90, BoatType.SKIFF))
    }

    @Test
    fun boatSurvivesDocumentRoundTrip() {
        val boat = Boat(5, "owner", BoatType.SLOOP, "Test")
        boat.damage(20)
        val copy = BoatStore.fromDocument(BoatStore.toDocument(boat))
        assertEquals(boat.hitpoints, copy.hitpoints)
        assertEquals(boat.type, copy.type)
    }
}
