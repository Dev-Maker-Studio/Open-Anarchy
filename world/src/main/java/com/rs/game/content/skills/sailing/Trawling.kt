package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.World
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import java.util.Optional
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class Depth(val label: String) { SHALLOW("shallow"), MODERATE("moderate"), DEEP("deep") }

enum class NetTier(
    val label: String,
    val sailingLevel: Int,
    val constructionLevel: Int,
    val wood: Wood,
    val planks: Int,
    val nails: Int,
    val bars: Int,
    val rope: Int,
    val maxFish: Int,
    val deepest: Depth,
    val deckhandiness: Int,
    val tokenXp: Double
) {
    ROPE("rope", 56, 45, Wood.MAHOGANY, 4, 16, 0, 10, 2, Depth.SHALLOW, 1, 7.0),
    LINEN("linen", 65, 61, Wood.CAMPHOR, 4, 16, 0, 15, 3, Depth.MODERATE, 2, 9.0),
    HEMP("hemp", 76, 65, Wood.CAMPHOR, 6, 24, 2, 20, 4, Depth.DEEP, 3, 11.0),
    COTTON("cotton", 84, 73, Wood.IRONWOOD, 6, 24, 4, 25, 5, Depth.DEEP, 4, 15.0);

    fun reaches(depth: Depth): Boolean = depth.ordinal <= deepest.ordinal
}

enum class TrawlFish(val item: Int, val fishingLevel: Int, val depths: Set<Depth>, val fishingXp: Double, val stopSeconds: Int, val minThreat: Int) {
    GIANT_KRILL(Obj.sailing_raw_giant_krill, 69, setOf(Depth.SHALLOW), 112.5, 90, 1),
    HADDOCK(Obj.sailing_raw_haddock, 73, setOf(Depth.SHALLOW), 128.5, 72, 3),
    YELLOWFIN(Obj.sailing_raw_yellowfin, 79, setOf(Depth.SHALLOW, Depth.MODERATE), 155.5, 60, 5),
    HALIBUT(Obj.sailing_raw_halibut, 83, setOf(Depth.SHALLOW, Depth.MODERATE), 195.5, 48, 8),
    BLUEFIN(Obj.sailing_raw_bluefin, 87, setOf(Depth.SHALLOW, Depth.MODERATE, Depth.DEEP), 220.5, 42, 12),
    MARLIN(Obj.sailing_raw_marlin, 91, setOf(Depth.SHALLOW, Depth.MODERATE, Depth.DEEP), 265.5, 30, 20);

    val stopTicks: Int get() = stopSeconds * TICKS_PER_TEN_SECONDS / SECONDS_PER_TEN_TICKS

    companion object {
        private const val TICKS_PER_TEN_SECONDS = 10
        private const val SECONDS_PER_TEN_TICKS = 6

        fun available(threat: Int): List<TrawlFish> = entries.filter { it.minThreat <= threat }
    }
}

class ShoalSite(val cellX: Int, val cellY: Int, val fish: TrawlFish, val depth: Depth, private val stops: List<Pair<Int, Int>>, private val phase: Int) {
    val key: String get() = "s:$cellX:$cellY"

    fun stopAt(ticks: Long): Pair<Int, Int> = stops[Math.floorMod(ticks / fish.stopTicks + phase, stops.size.toLong()).toInt()]
}

object ShoalField {
    const val CELL = 80
    private const val PERCENT = 16
    private const val STOPS = 3
    private const val SPREAD = 22
    private const val SALT = 0x53484F41L
    private const val MIX_X = 0x9E3779B97F4A7C15uL

    fun at(cellX: Int, cellY: Int): ShoalSite? {
        val random = Random(WorldGenerator.DEFAULT_SEED xor SALT xor (cellX * MIX_X.toLong()) xor (cellY.toLong() shl 20))
        if (random.nextInt(PERCENT_BASE) >= PERCENT) return null
        val centreX = cellX * CELL + random.nextInt(CELL)
        val centreY = cellY * CELL + random.nextInt(CELL)
        val stops = List(STOPS) { (centreX + random.nextInt(SPREAD * 2 + 1) - SPREAD) to (centreY + random.nextInt(SPREAD * 2 + 1) - SPREAD) }
        if (stops.any { (x, y) -> !Seas.clearWater(x, y) }) return null
        val fish = TrawlFish.available(PortSites.levelAt(centreX, centreY)).let { it[random.nextInt(it.size)] }
        val depths = fish.depths.toList()
        return ShoalSite(cellX, cellY, fish, depths[random.nextInt(depths.size)], stops, random.nextInt(STOPS))
    }

    private const val PERCENT_BASE = 100
}

class ShoalNPC(val site: ShoalSite, tile: Tile) : NPC(Npc.acs_fish_danio_shoal, tile, null, true) {
    init {
        setRandomWalk(false)
    }

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun randomWalk() {}

    override fun checkAggressivity(): Boolean = false
}

object Shoals {
    private const val GROUND_LEVEL = 0
    private const val SPAWN_RADIUS = 72
    private const val KEY_SHIFT = 32
    private const val KEY_MASK = 0xFFFFFFFFL
    private const val DESPAWN_RADIUS = 130
    private val sites = ConcurrentHashMap<Long, Optional<ShoalSite>>()
    private val spawned = ConcurrentHashMap<String, ShoalNPC>()

    fun sync(centres: List<Pair<Int, Int>>) {
        val now = World.getServerTicks()
        centres.forEach { (x, y) ->
            val minX = Math.floorDiv(x - SPAWN_RADIUS, ShoalField.CELL)
            val maxX = Math.floorDiv(x + SPAWN_RADIUS, ShoalField.CELL)
            val minY = Math.floorDiv(y - SPAWN_RADIUS, ShoalField.CELL)
            val maxY = Math.floorDiv(y + SPAWN_RADIUS, ShoalField.CELL)
            for (cy in minY..maxY) for (cx in minX..maxX) {
                val site = sites.computeIfAbsent(cx.toLong() shl KEY_SHIFT or (cy.toLong() and KEY_MASK)) { Optional.ofNullable(ShoalField.at(cx, cy)) }.orElse(null) ?: continue
                val (sx, sy) = site.stopAt(now)
                val tile = Tile.of(sx, sy, GROUND_LEVEL)
                val existing = spawned[site.key]
                if (existing == null) spawned[site.key] = ShoalNPC(site, tile) else if (existing.tile != tile) existing.tele(tile)
            }
        }
        spawned.values.toList().forEach { npc ->
            if (centres.none { (x, y) -> maxOf(abs(x - npc.x), abs(y - npc.y)) <= DESPAWN_RADIUS }) {
                npc.finish()
                spawned.remove(npc.site.key)
            }
        }
    }

    fun nearest(x: Int, y: Int, reach: Int): ShoalNPC? =
        spawned.values.filter { maxOf(abs(it.x - x), abs(it.y - y)) <= reach }.minByOrNull { maxOf(abs(it.x - x), abs(it.y - y)) }

    fun clear() {
        spawned.values.forEach { it.finish() }
        spawned.clear()
    }
}

class NetState(val tier: NetTier) {
    var depth = Depth.SHALLOW
    var active = false
    var lastTouch = 0L
    val catch = linkedMapOf<Int, Int>()

    val total: Int get() = catch.values.sum()
}

object Trawling {
    const val CAPACITY = 250
    const val REACH = 14
    private const val ROLL_TICKS = 3
    private const val CREW_ROLL_TICKS = 5
    private const val AUTO_STOP_TICKS = 500
    private const val BASE_CHANCE = 55
    private const val CHANCE_PER_LEVEL = 1
    private const val MAX_CHANCE = 95
    private const val PERCENT = 100
    private const val TRAWLER_KEG_PERCENT = 5
    private const val WRONG_DEPTH_DIVISOR = 2
    private val CREW_FRACTION = doubleArrayOf(0.0, 0.2, 0.4, 0.55, 0.7)

    fun crewFraction(deckhandiness: Int): Double = CREW_FRACTION[deckhandiness.coerceIn(0, CREW_FRACTION.size - 1)]

    fun successChance(fishingLevel: Int, required: Int): Int = (BASE_CHANCE + (fishingLevel - required) * CHANCE_PER_LEVEL).coerceIn(1, MAX_CHANCE)

    fun catchSize(maxFish: Int, rolled: Int, rightDepth: Boolean): Int = (1 + rolled.coerceIn(0, maxFish - 1)).let { if (rightDepth) it else maxOf(1, it / WRONG_DEPTH_DIVISOR) }

    fun open(player: Player, voyage: Voyage, index: Int) {
        val state = voyage.nets.getOrNull(index) ?: return
        val boat = voyage.boat
        player.sendOptionsDialogue("The ${state.tier.label} net holds ${state.total}/$CAPACITY fish") {
            Depth.entries.filter { state.tier.reaches(it) }.forEach { depth ->
                opExec("Lower the net (${depth.label} water)") { lower(player, voyage, state, depth) }
            }
            if (state.active) opExec("Haul in the net") { haul(player, state) }
            if (state.total > 0) opExec("Empty the net into the hold") { empty(player, boat, state) }
        }
    }

    private fun lower(player: Player, voyage: Voyage, state: NetState, depth: Depth) {
        if (player.skills.getLevel(Constants.SAILING) < state.tier.sailingLevel) return player.sendMessage("You need Sailing level ${state.tier.sailingLevel} to work a ${state.tier.label} net.")
        state.depth = depth
        state.active = true
        state.lastTouch = voyage.ticks
        val shoal = Shoals.nearest(voyage.tileX, voyage.tileY, REACH)
        player.sendMessage(if (shoal == null) "You lower the net into the ${depth.label} water. There is no shoal in range." else "You lower the net into the ${depth.label} water.")
    }

    private fun haul(player: Player, state: NetState) {
        state.active = false
        player.sendMessage("You haul in the net.")
    }

    private fun empty(player: Player, boat: Boat, state: NetState) {
        var moved = 0
        state.catch.entries.toList().forEach { (item, amount) ->
            val accepted = boat.hold.addGood(item, amount)
            moved += accepted
            if (accepted >= amount) state.catch.remove(item) else if (accepted > 0) state.catch[item] = amount - accepted
        }
        BoatStore.save(boat)
        player.sendMessage(if (moved > 0) "You empty $moved fish into the cargo hold." else "The cargo hold has no room for the catch.")
    }

    fun tick(voyage: Voyage, player: Player) {
        if (voyage.nets.isEmpty()) return
        voyage.nets.forEachIndexed { index, state ->
            if (!state.active) return@forEachIndexed
            if (voyage.ticks - state.lastTouch > AUTO_STOP_TICKS) {
                state.active = false
                return@forEachIndexed player.sendMessage("Your net drifts idle; click it to lower it again.")
            }
            if (voyage.ticks % ROLL_TICKS == 0L) roll(voyage, player, state, 1.0)
            if (voyage.ticks % CREW_ROLL_TICKS == 0L && voyage.sickTicks <= 0) {
                voyage.crewWith(CrewJob.TRAWL).filterIndexed { crewIndex, crewmate -> crewIndex % voyage.nets.size == index && crewmate.effectiveDeckhandiness(voyage.boat) >= state.tier.deckhandiness }
                    .forEach { roll(voyage, player, state, crewFraction(it.effectiveDeckhandiness(voyage.boat))) }
            }
        }
    }

    private fun roll(voyage: Voyage, player: Player, state: NetState, xpFraction: Double) {
        val shoal = Shoals.nearest(voyage.tileX, voyage.tileY, REACH) ?: return
        val fish = shoal.site.fish
        val fishing = player.skills.getLevel(Constants.FISHING)
        if (fishing < fish.fishingLevel) return
        if (state.total >= CAPACITY) return
        if (Utils.random(PERCENT) >= successChance(fishing, fish.fishingLevel)) return
        val rightDepth = shoal.site.depth == state.depth
        var amount = catchSize(state.tier.maxFish, Utils.random(state.tier.maxFish), rightDepth)
        if (voyage.boat.activeKeg == KegKind.TRAWLER && Utils.random(PERCENT) < TRAWLER_KEG_PERCENT) amount++
        amount = minOf(amount, CAPACITY - state.total)
        state.catch.merge(fish.item, amount, Int::plus)
        player.skills.addXp(Constants.FISHING, fish.fishingXp * xpFraction)
        SailingXp.grant(player, voyage.boat, state.tier.tokenXp * xpFraction / (if (rightDepth) 1 else WRONG_DEPTH_DIVISOR))
    }
}
