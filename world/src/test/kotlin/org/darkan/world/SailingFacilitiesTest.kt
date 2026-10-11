package org.darkan.world

import com.rs.game.content.skills.sailing.ActiveTask
import com.rs.game.content.skills.sailing.BagTier
import com.rs.game.content.skills.sailing.Boat
import com.rs.game.content.skills.sailing.BoatMovement
import com.rs.game.content.skills.sailing.BoatState
import com.rs.game.content.skills.sailing.BoatStore
import com.rs.game.content.skills.sailing.BoatType
import com.rs.game.content.skills.sailing.BountyMonster
import com.rs.game.content.skills.sailing.BountyTasks
import com.rs.game.content.skills.sailing.Crewmate
import com.rs.game.content.skills.sailing.Depth
import com.rs.game.content.skills.sailing.Facility
import com.rs.game.content.skills.sailing.HullTier
import com.rs.game.content.skills.sailing.KegKind
import com.rs.game.content.skills.sailing.KeelTier
import com.rs.game.content.skills.sailing.NetTier
import com.rs.game.content.skills.sailing.PortBagTables
import com.rs.game.content.skills.sailing.RepairRecipe
import com.rs.game.content.skills.sailing.SailTier
import com.rs.game.content.skills.sailing.SailTrim
import com.rs.game.content.skills.sailing.SailingXp
import com.rs.game.content.skills.sailing.Schematic
import com.rs.game.content.skills.sailing.TrawlFish
import com.rs.game.content.skills.sailing.Trawling
import com.rs.game.content.skills.sailing.Wind
import org.darkan.core.gameval.gen.Npc
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SailingFacilitiesTest {
    @Test
    fun trawlCatchSizeHalvesAtTheWrongDepth() {
        assertEquals(5, Trawling.catchSize(5, 4, true))
        assertEquals(2, Trawling.catchSize(5, 4, false))
        assertEquals(1, Trawling.catchSize(5, 0, false))
        assertEquals(5, Trawling.catchSize(5, 99, true))
    }

    @Test
    fun trawlSuccessChanceRisesWithFishingLevelAndIsCapped() {
        assertEquals(55, Trawling.successChance(69, 69))
        assertEquals(86, Trawling.successChance(100, 69))
        assertEquals(95, Trawling.successChance(200, 69))
        assertEquals(1, Trawling.successChance(1, 91))
    }

    @Test
    fun netTiersReachDeeperWater() {
        assertFalse(NetTier.ROPE.reaches(Depth.MODERATE))
        assertTrue(NetTier.LINEN.reaches(Depth.MODERATE))
        assertTrue(NetTier.COTTON.reaches(Depth.DEEP))
        assertEquals(150, TrawlFish.GIANT_KRILL.stopTicks)
    }

    @Test
    fun deeperThreatUnlocksBetterShoals() {
        assertEquals(listOf(TrawlFish.GIANT_KRILL), TrawlFish.available(1))
        assertEquals(TrawlFish.entries.toList(), TrawlFish.available(20))
    }

    @Test
    fun eternalAndAbyssalTiersStepUpFromRosewood() {
        assertEquals(9, HullTier.entries.size)
        assertEquals(288, HullTier.ETERNAL.hitpoints(BoatType.SLOOP))
        assertEquals(346, HullTier.ABYSSAL.hitpoints(BoatType.SLOOP))
        assertTrue(HullTier.ABYSSAL.hitpoints(BoatType.RAFT) > HullTier.ETERNAL.hitpoints(BoatType.RAFT))
        assertEquals(105, HullTier.ETERNAL.sailingLevel)
        assertEquals(117, SailTier.ABYSSAL.sailingLevel)
        assertEquals(117, KeelTier.ABYSSAL.sailingLevel)
        assertEquals(156.25, SailTier.ETERNAL.trimXp)
        assertEquals(Schematic.ETERNAL, HullTier.ETERNAL.schematic)
        assertNull(HullTier.ROSEWOOD.schematic)
    }

    @Test
    fun abyssalHullNeverExceedsTheHullSpeedCap() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test", hull = HullTier.ABYSSAL)
        val movement = BoatMovement(boat, 0.0, 0.0)
        repeat(5) { movement.addBurst(10) }
        assertEquals(4.0, movement.topSpeed)
        assertEquals(boat.maxHitpoints, boat.hitpoints)
    }

    @Test
    fun windHelpsHeadingsNearItsDirection() {
        assertTrue(Wind.helps(90, 90))
        assertTrue(Wind.helps(350, 10))
        assertFalse(Wind.helps(90, 150))
        assertEquals(Wind.directionAt("MN", 1_000L), Wind.directionAt("MN", 1_000L))
        assertTrue(Wind.directionAt("MN", 123_456L) in 0 until 360)
    }

    @Test
    fun catchersStoreMotesAndPayLessForCrystalMotes() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        val movement = BoatMovement(boat, 0.0, 0.0)
        val trim = SailTrim(boat, movement)
        trim.windCatcher = true
        movement.sailsSet = true
        var guard = 0
        while (!trim.gustOpen && guard++ < 100) trim.tick()
        assertEquals(10.5 * 0.75, trim.trim(1, true))
        assertEquals(1, trim.motes)
        assertTrue(trim.addMote(true))
        assertFalse(trim.addMote(true))
        assertEquals(10.0, trim.releaseMote())
        assertEquals(40.0, trim.releaseMote())
        assertEquals(0.0, trim.releaseMote())
    }

    @Test
    fun galeCatcherHoldsThreeMotes() {
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        val trim = SailTrim(boat, BoatMovement(boat, 0.0, 0.0))
        trim.galeCatcher = true
        repeat(3) { assertTrue(trim.addMote(false)) }
        assertFalse(trim.addMote(false))
        assertEquals(70.0, trim.releaseMote())
    }

    @Test
    fun facilitiesKeepTheirHotspotRules() {
        assertTrue(Facility.WIND_CATCHER.conflictsWith(Facility.GALE_CATCHER))
        assertTrue(Facility.TELEPORT_FOCUS.conflictsWith(Facility.GREATER_TELEPORT_FOCUS))
        assertFalse(Facility.ANCHOR.conflictsWith(Facility.KEG))
        assertTrue(Facility.GALE_CATCHER.raftAllowed)
        assertFalse(Facility.CRYSTAL_EXTRACTOR.raftAllowed)
        val boat = Boat(1, "owner", BoatType.SKIFF, "Test")
        boat.nets.add(NetTier.ROPE)
        boat.facilities.add(Facility.KEG)
        assertEquals(2, boat.hotspotsUsed)
        assertEquals(1, boat.maxNets)
        assertEquals(0, Boat(2, "owner", BoatType.RAFT, "Raft").maxNets)
    }

    @Test
    fun aKegOnlyActsWhileFittedAndStocked() {
        val boat = Boat(1, "owner", BoatType.SLOOP, "Test")
        boat.keg = KegKind.HORIZON
        boat.kegDoses = 5
        assertNull(boat.activeKeg)
        boat.facilities.add(Facility.KEG)
        assertEquals(KegKind.HORIZON, boat.activeKeg)
        assertEquals(1.025, SailingXp.multiplier(boat))
        boat.kegDoses = 0
        assertEquals(1.0, SailingXp.multiplier(boat))
        assertEquals(1.0, SailingXp.multiplier(null))
    }

    @Test
    fun whirlpoolKegRaisesWeakCrewToTwo() {
        val boat = Boat(1, "owner", BoatType.SLOOP, "Test", facilities = mutableSetOf(Facility.KEG), keg = KegKind.WHIRLPOOL, kegDoses = 3)
        val weak = Crewmate("Weak", 1, 1, 1, 10, 40)
        val strong = Crewmate("Strong", 4, 4, 4, 10, 40)
        assertEquals(2, weak.effectiveDeckhandiness(boat))
        assertEquals(2, weak.effectiveHelmsmanship(boat))
        assertEquals(4, strong.effectiveDeckhandiness(boat))
        assertEquals(1, weak.effectiveDeckhandiness(Boat(2, "owner", BoatType.SLOOP, "Plain")))
    }

    @Test
    fun boatFacilitiesAndKegSurviveTheDocumentRoundTrip() {
        val boat = Boat(
            7, "owner", BoatType.SLOOP, "Test", hull = HullTier.ETERNAL, sail = SailTier.ABYSSAL, keel = KeelTier.ETERNAL,
            facilities = mutableSetOf(Facility.KEG, Facility.GALE_CATCHER, Facility.CRYSTAL_EXTRACTOR), nets = mutableListOf(NetTier.HEMP, NetTier.COTTON),
            keg = KegKind.TRAWLER, kegDoses = 12, state = BoatState.BOTTLED
        )
        val copy = BoatStore.fromDocument(BoatStore.toDocument(boat))
        assertEquals(boat.facilities, copy.facilities)
        assertEquals(boat.nets, copy.nets)
        assertEquals(KegKind.TRAWLER, copy.keg)
        assertEquals(12, copy.kegDoses)
        assertEquals(BoatState.BOTTLED, copy.state)
        assertEquals(HullTier.ETERNAL, copy.hull)
        assertEquals(SailTier.ABYSSAL, copy.sail)
        assertEquals(KeelTier.ETERNAL, copy.keel)
    }

    @Test
    fun everyBagTierHasAWellFormedTable() {
        val random = Random(42)
        BagTier.entries.forEach { tier ->
            val table = PortBagTables.table(tier)
            assertTrue(table.isNotEmpty())
            assertTrue(table.all { it.min <= it.max && it.weight > 0.0 })
            repeat(200) {
                val (item, amount) = PortBagTables.roll(tier, random)
                assertTrue(table.any { it.item == item && amount in it.min..it.max })
            }
        }
    }

    @Test
    fun repairKitRecipesFollowTheTierLadder() {
        assertEquals(listOf(5, 10, 20, 30, 40, 45, 50), RepairRecipe.entries.map { it.kit.heals })
        assertEquals(3, RepairRecipe.ROSEWOOD.yield)
        assertEquals(2, RepairRecipe.OAK.yield)
        assertFalse(RepairRecipe.WOODEN.needsTools)
        assertTrue(RepairRecipe.TEAK.needsTools)
        assertTrue(RepairRecipe.entries.zipWithNext().all { (a, b) -> a.constructionLevel < b.constructionLevel })
    }

    @Test
    fun bountyItemsAreCountedAcrossTheHold() {
        val boat = Boat(1, "owner", BoatType.SLOOP, "Test")
        val monster = BountyMonster.LOBSTER
        boat.hold.goods[monster.first] = 3
        boat.hold.goods[monster.second] = 2
        assertEquals(5, BountyTasks.itemsHeld(listOf(boat), monster))
        assertEquals(0, BountyTasks.itemsHeld(listOf(boat), BountyMonster.SEA_TROLL))
        assertEquals(BountyMonster.LOBSTER, BountyMonster.ofNpc(Npc.giant_lobster))
        assertNull(BountyMonster.ofNpc(Npc.sailing_raft))
        assertNotEquals(BountyTasks.taskId("MN-1", BountyMonster.LOBSTER), BountyTasks.taskId("MN-1", BountyMonster.SEA_TROLL))
        assertEquals(ActiveTask.NO_MONSTER, -1)
    }

    @Test
    fun bountiesGetHarderWithLevel() {
        val levels = BountyMonster.entries.map { it.minLevel }
        assertEquals(levels.sorted(), levels)
        assertTrue(BountyMonster.entries.zipWithNext().all { (a, b) -> a.taskXp < b.taskXp })
        assertEquals(30, levels.first())
    }
}
