package org.darkan.world

import com.rs.game.content.skills.sailing.PortKind
import com.rs.game.content.skills.sailing.TrialCourse
import com.rs.game.content.skills.sailing.TrialLayouts
import com.rs.game.content.skills.sailing.TrialRank
import com.rs.game.content.skills.sailing.TrialScoring
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SailingTrialsTest {
    @Test
    fun ranksSetTheNumberOfLaps() {
        assertEquals(1, TrialScoring.laps(TrialRank.NONE))
        assertEquals(1, TrialScoring.laps(TrialRank.SWORDFISH))
        assertEquals(2, TrialScoring.laps(TrialRank.SHARK))
        assertEquals(3, TrialScoring.laps(TrialRank.MARLIN))
    }

    @Test
    fun targetTimeScalesWithTheGeneratedLapLength() {
        val course = TrialCourse.LAKE_DASH
        assertEquals(180, TrialScoring.targetTicks(course, TrialRank.SWORDFISH, course.lapTiles))
        assertEquals(90, TrialScoring.targetTicks(course, TrialRank.SWORDFISH, course.lapTiles / 2))
        assertEquals(Int.MAX_VALUE, TrialScoring.targetTicks(course, TrialRank.NONE, course.lapTiles))
    }

    @Test
    fun aRankIsOnlyEarnedInsideTheTargetTime() {
        val course = TrialCourse.REEF_RUN
        val target = TrialScoring.targetTicks(course, TrialRank.SHARK, course.lapTiles)
        assertEquals(TrialRank.SHARK, TrialScoring.achieved(course, TrialRank.SHARK, target, course.lapTiles))
        assertEquals(TrialRank.NONE, TrialScoring.achieved(course, TrialRank.SHARK, target + 1, course.lapTiles))
        assertEquals(TrialRank.NONE, TrialScoring.achieved(course, TrialRank.NONE, 1, course.lapTiles))
    }

    @Test
    fun firstClearBonusPaysEveryRankSkipped() {
        val course = TrialCourse.LAKE_DASH
        assertEquals(6_000, TrialScoring.firstClearBonus(course, TrialRank.MARLIN, TrialRank.NONE))
        assertEquals(3_000, TrialScoring.firstClearBonus(course, TrialRank.MARLIN, TrialRank.SHARK))
        assertEquals(0, TrialScoring.firstClearBonus(course, TrialRank.SHARK, TrialRank.MARLIN))
        assertEquals(16_050, TrialScoring.completionXp(TrialCourse.DEEP_GAUNTLET, TrialRank.MARLIN))
    }

    @Test
    fun ringHasTheCourseCheckpointsAndCrates() {
        val course = TrialCourse.DEEP_GAUNTLET
        val layout = assertNotNull(TrialLayouts.ring(1_000, 1_000, 1, 0, course) { _, _ -> true })
        assertEquals(course.checkpoints, layout.checkpoints.size)
        assertEquals(course.cratesPerLap, layout.crates.size)
        assertTrue(abs(layout.lapLength - course.lapTiles) <= 1)
    }

    @Test
    fun ringShrinksToFitAndGivesUpWhenBlocked() {
        val course = TrialCourse.LAKE_DASH
        val tight = TrialLayouts.ring(0, 0, 1, 0, course) { x, y -> x * x + y * y < 60 * 60 || x < 0 }
        assertTrue(tight == null || tight.lapLength < course.lapTiles)
        assertNull(TrialLayouts.ring(0, 0, 1, 0, course) { _, _ -> false })
    }

    @Test
    fun lakeAndOceanPortsOfferDifferentCourses() {
        assertEquals(listOf(TrialCourse.LAKE_DASH), TrialCourse.at(PortKind.LAKE))
        assertEquals(2, TrialCourse.at(PortKind.OCEAN).size)
    }
}
