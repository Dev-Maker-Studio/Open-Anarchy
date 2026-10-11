package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TrialRank(val label: String) {
    NONE("Unranked"), SWORDFISH("Swordfish"), SHARK("Shark"), MARLIN("Marlin")
}

enum class TrialCourse(
    val label: String,
    val sailingLevel: Int,
    val ocean: Boolean,
    val lapTiles: Int,
    val checkpoints: Int,
    val cratesPerLap: Int,
    val crateXp: Int,
    val completionXp: List<Int>,
    val bonusXp: List<Int>,
    val targetSeconds: List<Int>,
    val minHelm: HelmTier,
    val minSail: SailTier,
    val needsInoculation: Boolean,
    val minKeel: KeelTier?
) {
    LAKE_DASH(
        "Lake Dash", 30, false, 250, 8, 12, 15, listOf(150, 385, 650, 1_250), listOf(1_000, 2_000, 3_000), listOf(108, 171, 270),
        HelmTier.IRON, SailTier.OAK, false, null
    ),
    REEF_RUN(
        "Reef Run", 55, true, 300, 10, 19, 25, listOf(1_000, 1_700, 3_000, 6_200), listOf(5_000, 7_500, 10_000), listOf(123, 180, 321),
        HelmTier.MITHRIL, SailTier.WOODEN, true, null
    ),
    DEEP_GAUNTLET(
        "Deep Gauntlet", 72, true, 380, 12, 24, 45, listOf(2_050, 3_050, 7_250, 16_050), listOf(25_000, 35_000, 50_000), listOf(120, 222, 369),
        HelmTier.ADAMANT, SailTier.WOODEN, false, KeelTier.ADAMANT
    );

    companion object {
        fun at(kind: PortKind): List<TrialCourse> = entries.filter { it.ocean == (kind == PortKind.OCEAN) }
    }
}

object TrialScoring {
    private const val TICKS_PER_TEN_SECONDS = 10
    private const val SECONDS_PER_TEN_TICKS = 6
    const val CRATE_BONUS_TICKS = 2

    fun laps(rank: TrialRank): Int = maxOf(1, rank.ordinal)

    fun targetTicks(course: TrialCourse, rank: TrialRank, lapLength: Int): Int {
        if (rank == TrialRank.NONE) return Int.MAX_VALUE
        val nominal = course.targetSeconds[rank.ordinal - 1] * TICKS_PER_TEN_SECONDS / SECONDS_PER_TEN_TICKS
        return (nominal.toDouble() * lapLength / course.lapTiles).roundToInt()
    }

    fun achieved(course: TrialCourse, chosen: TrialRank, elapsedTicks: Int, lapLength: Int): TrialRank =
        if (chosen != TrialRank.NONE && elapsedTicks <= targetTicks(course, chosen, lapLength)) chosen else TrialRank.NONE

    fun completionXp(course: TrialCourse, achieved: TrialRank): Int = course.completionXp[achieved.ordinal]

    fun firstClearBonus(course: TrialCourse, achieved: TrialRank, previous: TrialRank): Int =
        if (achieved <= previous) 0 else (previous.ordinal until achieved.ordinal).sumOf { course.bonusXp[it] }
}

class TrialLayout(val centreX: Int, val centreY: Int, val radius: Int, val checkpoints: List<Pair<Int, Int>>, val crates: List<Pair<Int, Int>>, val lapLength: Int)

object TrialLayouts {
    private val SHRINK = listOf(1.0, 0.85, 0.7, 0.55, 0.45)
    private const val ENTRY_GAP = 6
    private const val ARC_STEP = 6.0
    private const val CRATE_OFFSET = 3
    private const val MIN_RADIUS = 16.0

    fun ring(shoreX: Int, shoreY: Int, dirX: Int, dirY: Int, course: TrialCourse, isClear: (Int, Int) -> Boolean): TrialLayout? {
        val nominal = course.lapTiles / (2 * PI)
        for (factor in SHRINK) {
            val radius = nominal * factor
            if (radius < MIN_RADIUS) continue
            val centreX = shoreX + dirX * (radius.roundToInt() + ENTRY_GAP)
            val centreY = shoreY + dirY * (radius.roundToInt() + ENTRY_GAP)
            val start = atan2(-dirY.toDouble(), -dirX.toDouble())
            val checkpoints = List(course.checkpoints) { pointOn(centreX, centreY, radius, start + 2 * PI * it / course.checkpoints, 0) }
            val crates = List(course.cratesPerLap) { pointOn(centreX, centreY, radius, start + 2 * PI * (it + 0.5) / course.cratesPerLap, if (it % 2 == 0) CRATE_OFFSET else -CRATE_OFFSET) }
            val samples = (2 * PI * radius / ARC_STEP).toInt().coerceAtLeast(course.checkpoints)
            val arc = List(samples) { pointOn(centreX, centreY, radius, start + 2 * PI * it / samples, 0) }
            if ((checkpoints + crates + arc).all { (x, y) -> isClear(x, y) }) return TrialLayout(centreX, centreY, radius.roundToInt(), checkpoints, crates, (2 * PI * radius).roundToInt())
        }
        return null
    }

    private fun pointOn(centreX: Int, centreY: Int, radius: Double, angle: Double, radial: Int): Pair<Int, Int> =
        (centreX + (radius + radial) * cos(angle)).roundToInt() to (centreY + (radius + radial) * sin(angle)).roundToInt()
}

class TrialRun(val course: TrialCourse, val rank: TrialRank, val layout: TrialLayout) {
    var startTick = -1L
    var lap = 0
    var next = 1
    var crates = 0
    var bonusTicks = 0
    var awayTicks = 0
    val buoys = mutableListOf<GameObject>()
    val crateObjects = mutableMapOf<Int, GameObject>()

    val laps: Int get() = TrialScoring.laps(rank)

    fun elapsed(now: Long): Int = if (startTick < 0) 0 else (now - startTick).toInt() - bonusTicks
}

object Barracuda {
    private const val GROUND_LEVEL = 0
    private const val CHECKPOINT_REACH = 6
    private const val CRATE_REACH = 3
    private const val AWAY_MARGIN = 70
    private const val AWAY_LIMIT_TICKS = 17
    private const val LOCATION_ROTATION = 0
    private const val TICKS_PER_SECOND_TENTHS = 6

    fun menu(player: Player, site: PortSite) {
        val courses = TrialCourse.at(site.kind)
        player.sendOptionsDialogue("Barracuda Trials at port ${site.name}") {
            courses.forEach { course ->
                opExec("${course.label} (Sailing ${course.sailingLevel})") { rankMenu(player, site, course) }
            }
        }
    }

    private fun rankMenu(player: Player, site: PortSite, course: TrialCourse) {
        val best = SailorStore.of(player.username).rank(course)
        player.sendOptionsDialogue("${course.label}: your best rank is ${best.label}") {
            TrialRank.entries.forEach { rank ->
                opExec("${rank.label} (${TrialScoring.laps(rank)} lap${if (TrialScoring.laps(rank) > 1) "s" else ""})") { start(player, site, course, rank) }
            }
        }
    }

    fun refusal(player: Player, boat: Boat, course: TrialCourse): String? = when {
        player.skills.getLevelForXp(Constants.SAILING) < course.sailingLevel -> "You need Sailing level ${course.sailingLevel} for the ${course.label}."
        boat.type != BoatType.SKIFF -> "The trial master only lets a skiff race."
        boat.helm < course.minHelm -> "The ${course.label} needs at least a ${course.minHelm.name.lowercase()} helm."
        boat.sail < course.minSail -> "The ${course.label} needs at least ${course.minSail.name.lowercase()} sails."
        course.needsInoculation && !boat.hasInoculationStation && Facility.INOCULATION_STATION !in boat.facilities -> "The ${course.label} needs an inoculation station aboard."
        course.minKeel != null && (boat.keel == null || boat.keel!! < course.minKeel) -> "The ${course.label} needs at least a ${course.minKeel.name.lowercase()} keel."
        else -> null
    }

    private fun start(player: Player, site: PortSite, course: TrialCourse, rank: TrialRank) {
        val aboard = Voyages.of(player)
        if (aboard != null) return begin(player, aboard, site, course, rank)
        Shipwright.chooseBoat(player, "Race which skiff?", { it.type == BoatType.SKIFF && it.state == BoatState.DOCKED && it.dockPort == site.name }) { boat ->
            refusal(player, boat, course)?.let { return@chooseBoat player.sendMessage(it) }
            Voyages.start(player, boat, site)
            Voyages.of(player)?.let { begin(player, it, site, course, rank) }
        }
    }

    fun begin(player: Player, voyage: Voyage, site: PortSite, course: TrialCourse, rank: TrialRank) {
        refusal(player, voyage.boat, course)?.let { return player.sendMessage(it) }
        if (voyage.trial != null) return player.sendMessage("You are already racing.")
        if (!site.inZone(voyage.tileX, voyage.tileY, TRIAL_ZONE_MARGIN)) return player.sendMessage("Start a trial from the waters of its port.")
        val layout = TrialLayouts.ring(site.shoreX, site.shoreY, site.dirX, site.dirY, course) { x, y -> Seas.clearWater(x, y) }
            ?: return player.sendMessage("There is not enough open water at port ${site.name} for the ${course.label}.")
        val run = TrialRun(course, rank, layout)
        layout.checkpoints.forEach { (x, y) -> run.buoys.add(spawn(Loc.sailing_trial_buoy, x, y)) }
        voyage.boat.crew.forEach { it.job = CrewJob.NONE }
        voyage.trial = run
        spawnCrates(run)
        player.sendMessage("${course.label}, ${rank.label}: sail the ring of buoys ${TrialScoring.laps(rank)} time(s), grabbing crates. The clock starts when you move; leaving the course for ten seconds ends the run.")
    }

    private fun spawn(loc: Int, x: Int, y: Int): GameObject {
        val obj = GameObject(loc, ObjectShape.SCENERY_INTERACT, LOCATION_ROTATION, Tile.of(x, y, GROUND_LEVEL))
        World.spawnObject(obj, false)
        return obj
    }

    private fun spawnCrates(run: TrialRun) {
        run.crateObjects.values.forEach { World.removeObject(it) }
        run.crateObjects.clear()
        run.layout.crates.forEachIndexed { index, (x, y) -> run.crateObjects[index] = spawn(Loc.sailing_trial_crate, x, y) }
    }

    fun cleanup(run: TrialRun) {
        run.buoys.forEach { World.removeObject(it) }
        run.buoys.clear()
        run.crateObjects.values.forEach { World.removeObject(it) }
        run.crateObjects.clear()
    }

    fun abort(voyage: Voyage, player: Player?, reason: String) {
        val run = voyage.trial ?: return
        cleanup(run)
        voyage.trial = null
        player?.sendMessage(reason)
    }

    fun tick(voyage: Voyage, player: Player) {
        val run = voyage.trial ?: return
        if (run.startTick < 0 && voyage.movement.speed != 0.0) run.startTick = voyage.ticks
        if (maxOf(abs(voyage.tileX - run.layout.centreX), abs(voyage.tileY - run.layout.centreY)) > run.layout.radius + AWAY_MARGIN) {
            if (++run.awayTicks > AWAY_LIMIT_TICKS) return abort(voyage, player, "You left the course for too long; the run is void.")
        } else {
            run.awayTicks = 0
        }
        collectCrates(voyage, player, run)
        val (checkpointX, checkpointY) = run.layout.checkpoints[run.next]
        if (maxOf(abs(voyage.tileX - checkpointX), abs(voyage.tileY - checkpointY)) > CHECKPOINT_REACH) return
        if (run.next == 0) {
            run.lap++
            if (run.lap >= run.laps) return finish(voyage, player, run)
            run.next = 1
            spawnCrates(run)
            player.sendMessage("Lap ${run.lap} of ${run.laps} done (${seconds(run.elapsed(voyage.ticks))}).")
        } else {
            run.next = (run.next + 1) % run.layout.checkpoints.size
        }
    }

    private fun collectCrates(voyage: Voyage, player: Player, run: TrialRun) {
        run.crateObjects.entries.toList().forEach { (index, obj) ->
            if (maxOf(abs(obj.tile.x - voyage.tileX), abs(obj.tile.y - voyage.tileY)) > CRATE_REACH) return@forEach
            World.removeObject(obj)
            run.crateObjects.remove(index)
            run.crates++
            run.bonusTicks += TrialScoring.CRATE_BONUS_TICKS
            SailingXp.grant(player, voyage.boat, run.course.crateXp.toDouble())
        }
    }

    private fun finish(voyage: Voyage, player: Player, run: TrialRun) {
        val elapsed = run.elapsed(voyage.ticks)
        val achieved = TrialScoring.achieved(run.course, run.rank, elapsed, run.layout.lapLength)
        val profile = SailorStore.of(player.username)
        val bonus = TrialScoring.firstClearBonus(run.course, achieved, profile.rank(run.course))
        SailingXp.grant(player, voyage.boat, (TrialScoring.completionXp(run.course, achieved) + bonus).toDouble())
        profile.trialBests.merge(run.course.name, elapsed, ::minOf)
        if (bonus > 0) profile.trialRanks[run.course.name] = achieved.ordinal
        SailorStore.save(profile)
        cleanup(run)
        voyage.trial = null
        val target = TrialScoring.targetTicks(run.course, run.rank, run.layout.lapLength)
        player.sendMessage(
            "${run.course.label} complete in ${seconds(elapsed)} with ${run.crates} crates." +
                if (run.rank == TrialRank.NONE) " Practice run." else if (achieved == TrialRank.NONE) " Missed the ${run.rank.label} target of ${seconds(target)}." else " ${achieved.label} rank earned!" + if (bonus > 0) " First clear bonus: $bonus xp." else ""
        )
    }

    fun status(player: Player, voyage: Voyage) {
        val run = voyage.trial ?: return player.sendMessage("You are not in a trial. Ask a Shipwright about the Barracuda Trials.")
        val target = TrialScoring.targetTicks(run.course, run.rank, run.layout.lapLength)
        player.sendMessage(
            "${run.course.label} (${run.rank.label}): lap ${run.lap + 1}/${run.laps}, buoy ${run.next}/${run.layout.checkpoints.size}, ${run.crates} crates, " +
                "${seconds(run.elapsed(voyage.ticks))}${if (run.rank != TrialRank.NONE) " of ${seconds(target)}" else ""}."
        )
    }

    private fun seconds(ticks: Int): String = "%.1fs".format(ticks * TICKS_PER_SECOND_TENTHS / TENTHS.toDouble())

    private const val TENTHS = 10
    private const val TRIAL_ZONE_MARGIN = 20
}
