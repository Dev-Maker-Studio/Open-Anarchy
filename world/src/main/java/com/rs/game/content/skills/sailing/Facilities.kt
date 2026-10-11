package com.rs.game.content.skills.sailing

import org.darkan.core.gameval.gen.Obj

enum class Facility(
    val label: String,
    val sailingLevel: Int,
    val constructionLevel: Int,
    val wood: Wood,
    val planks: Int,
    val nails: Int,
    val bars: Int,
    val raftAllowed: Boolean = false
) {
    ANCHOR("anchor", 37, 29, Wood.TEAK, 4, 16, 6),
    INOCULATION_STATION("inoculation station", 40, 37, Wood.TEAK, 8, 32, 0),
    WIND_CATCHER("wind catcher", 53, 47, Wood.TEAK, 4, 16, 8, raftAllowed = true),
    GALE_CATCHER("gale catcher", 79, 70, Wood.CAMPHOR, 4, 16, 8, raftAllowed = true),
    BRAZIER("eternal brazier", 45, 38, Wood.MAHOGANY, 4, 12, 8),
    KEG("keg", 33, 25, Wood.OAK, 5, 20, 0),
    SALVAGING_STATION("salvaging station", 42, 34, Wood.TEAK, 4, 16, 0),
    CRYSTAL_EXTRACTOR("crystal extractor", 73, 67, Wood.IRONWOOD, 6, 0, 5),
    TELEPORT_FOCUS("teleport focus", 55, 49, Wood.MAHOGANY, 8, 32, 4),
    GREATER_TELEPORT_FOCUS("greater teleport focus", 75, 69, Wood.IRONWOOD, 8, 32, 4);

    val catcher: Boolean get() = this == WIND_CATCHER || this == GALE_CATCHER

    val focus: Boolean get() = this == TELEPORT_FOCUS || this == GREATER_TELEPORT_FOCUS

    fun conflictsWith(other: Facility): Boolean = (catcher && other.catcher) || (focus && other.focus)
}

enum class CannonTier(val label: String, val sailingLevel: Int, val rangedLevel: Int, val constructionLevel: Int, val wood: Wood, val strength: Int, val ballItem: Int) {
    BRONZE("bronze", 28, 1, 21, Wood.WOODEN, 8, Obj.sailing_cannonball_bronze),
    IRON("iron", 35, 1, 28, Wood.OAK, 10, Obj.sailing_cannonball_iron),
    STEEL("steel", 47, 5, 39, Wood.TEAK, 12, Obj.sailing_cannonball_steel),
    MITHRIL("mithril", 57, 20, 50, Wood.MAHOGANY, 15, Obj.sailing_cannonball_mithril),
    ADAMANT("adamant", 69, 30, 61, Wood.CAMPHOR, 23, Obj.sailing_cannonball_adamant),
    RUNE("rune", 80, 40, 76, Wood.IRONWOOD, 26, Obj.sailing_cannonball_rune),
    DRAGON("dragon", 92, 60, 84, Wood.ROSEWOOD, 32, Obj.sailing_cannonball_dragon);

    companion object {
        const val PLANKS = 4
        const val NAILS = 16
        const val BARS = 8
        const val RANGE_TILES = 15
        const val ATTACK_TICKS = 7
        const val BALL_STRENGTH = 43
        const val ARC_DEGREES = 90
    }
}

enum class HookTier(val label: String, val sailingLevel: Int, val constructionLevel: Int, val wood: Wood, val successPercent: Int) {
    BRONZE("bronze", 15, 1, Wood.WOODEN, 30),
    IRON("iron", 21, 9, Wood.OAK, 38),
    STEEL("steel", 27, 18, Wood.TEAK, 46),
    MITHRIL("mithril", 44, 30, Wood.MAHOGANY, 54),
    ADAMANT("adamant", 59, 52, Wood.CAMPHOR, 62),
    RUNE("rune", 74, 66, Wood.IRONWOOD, 70),
    DRAGON("dragon", 86, 78, Wood.ROSEWOOD, 80);

    companion object {
        const val PLANKS = 4
        const val NAILS = 16
        const val BARS = 6
    }
}

class CargoHold(var capacity: Int) {
    val crates = mutableMapOf<Long, Int>()
    val goods = linkedMapOf<Int, Int>()
    var cannonballs = 0
    var sortingXp = 0.0

    val used: Int get() = crates.values.sum() + goods.values.sum()

    val free: Int get() = (capacity - used).coerceAtLeast(0)

    fun addGood(item: Int, amount: Int): Int {
        val accepted = minOf(amount, free)
        if (accepted > 0) goods.merge(item, accepted, Int::plus)
        return accepted
    }

    fun addCrates(task: Long, amount: Int): Int {
        val accepted = minOf(amount, free)
        if (accepted > 0) crates.merge(task, accepted, Int::plus)
        return accepted
    }

    fun clearLost() {
        crates.clear()
        goods.clear()
    }
}

object Materials {
    private val PLANK = mapOf(
        Wood.WOODEN to Obj.woodplank, Wood.OAK to Obj.plank_oak, Wood.TEAK to Obj.plank_teak, Wood.MAHOGANY to Obj.plank_mahogany,
        Wood.CAMPHOR to Obj.plank_mahogany, Wood.IRONWOOD to Obj.plank_mahogany, Wood.ROSEWOOD to Obj.plank_mahogany,
        Wood.ETERNAL to Obj.plank_mahogany, Wood.ABYSSAL to Obj.plank_mahogany
    )
    private val NAIL = mapOf(
        Wood.WOODEN to Obj.nails_bronze, Wood.OAK to Obj.nails_iron, Wood.TEAK to Obj.nails, Wood.MAHOGANY to Obj.nails_mithril,
        Wood.CAMPHOR to Obj.obj_4823, Wood.IRONWOOD to Obj.nails_rune, Wood.ROSEWOOD to Obj.nails_rune,
        Wood.ETERNAL to Obj.nails_rune, Wood.ABYSSAL to Obj.nails_rune
    )
    private val BAR = mapOf(
        Wood.WOODEN to Obj.bronze_bar, Wood.OAK to Obj.iron_bar, Wood.TEAK to Obj.steel_bar, Wood.MAHOGANY to Obj.mithril_bar,
        Wood.CAMPHOR to Obj.adamantite_bar, Wood.IRONWOOD to Obj.runite_bar, Wood.ROSEWOOD to Obj.runite_bar,
        Wood.ETERNAL to Obj.runite_bar, Wood.ABYSSAL to Obj.runite_bar
    )
    private val GEM = mapOf(Wood.ETERNAL to Obj.dragonstone, Wood.ABYSSAL to Obj.onyx)
    private val METAL = listOf(Obj.bronze_bar, Obj.iron_bar, Obj.steel_bar, Obj.mithril_bar, Obj.adamantite_bar, Obj.runite_bar, Obj.runite_bar, Obj.runite_bar, Obj.runite_bar)
    private val BYPASS_FEES = intArrayOf(3_400, 7_200, 22_000, 68_000)
    private const val HULL_PLANKS_RAFT = 10
    private const val HULL_PLANKS_SKIFF = 20
    private const val HULL_PLANKS_SLOOP = 40
    private const val HULL_EXTRA_BARS = 5
    private const val HULL_EXTRA_FROM_TEAK = 2
    private const val HELM_PLANKS_BASE = 1
    private const val HELM_BARS_STEP = 2
    private const val SAIL_PLANKS_RAFT = 4
    private const val SAIL_PLANKS_SKIFF = 6
    private const val SAIL_PLANKS_SLOOP = 10
    private const val KEEL_BARS_SKIFF = 8
    private const val KEEL_BARS_SLOOP = 14
    private const val NAILS_PER_PLANK = 2
    private const val DRAGON_BAR_FACTOR = 2
    private const val HULL_GEMS = 5
    private const val SAIL_GEMS = 3
    private const val KEEL_GEMS = 4

    class Cost(val items: Map<Int, Int>) {
        fun affordableBy(have: (Int) -> Int): Boolean = items.all { (id, amount) -> have(id) >= amount }

        fun describe(name: (Int) -> String): String = items.entries.joinToString(", ") { "${it.value} ${name(it.key)}" }
    }

    fun hullCost(wood: Wood, type: BoatType): Cost {
        val planks = when (type) {
            BoatType.RAFT -> HULL_PLANKS_RAFT
            BoatType.SKIFF -> HULL_PLANKS_SKIFF
            BoatType.SLOOP -> HULL_PLANKS_SLOOP
        }
        val extra = if (wood.ordinal >= HULL_EXTRA_FROM_TEAK) HULL_EXTRA_BARS else 0
        return cost(wood, planks, planks * NAILS_PER_PLANK, extra, HULL_GEMS)
    }

    fun helmCost(tier: HelmTier, type: BoatType): Cost {
        val scale = type.ordinal + 1
        val bars = HELM_BARS_STEP * (scale + 1) * if (tier == HelmTier.DRAGON) DRAGON_BAR_FACTOR else 1
        return Cost(mapOf(Obj.woodplank to HELM_PLANKS_BASE + scale, METAL[tier.ordinal] to bars))
    }

    fun sailCost(wood: Wood, type: BoatType): Cost {
        val planks = when (type) {
            BoatType.RAFT -> SAIL_PLANKS_RAFT
            BoatType.SKIFF -> SAIL_PLANKS_SKIFF
            BoatType.SLOOP -> SAIL_PLANKS_SLOOP
        }
        return cost(wood, planks, planks * NAILS_PER_PLANK, 0, SAIL_GEMS)
    }

    fun keelCost(tier: KeelTier, type: BoatType): Cost {
        val bars = if (type == BoatType.SLOOP) KEEL_BARS_SLOOP else KEEL_BARS_SKIFF
        val items = linkedMapOf(METAL[tier.ordinal] to bars * if (tier == KeelTier.DRAGON) DRAGON_BAR_FACTOR else 1)
        Wood.entries.getOrNull(tier.ordinal)?.let { GEM[it] }?.let { items[it] = KEEL_GEMS }
        return Cost(items)
    }

    fun netCost(tier: NetTier): Cost {
        val items = linkedMapOf<Int, Int>()
        items.putAll(cost(tier.wood, tier.planks, tier.nails, tier.bars).items)
        items[Obj.rope] = tier.rope
        return Cost(items)
    }

    fun plank(wood: Wood): Int = PLANK.getValue(wood)

    fun nail(wood: Wood): Int = NAIL.getValue(wood)

    val SWAMP_PASTE: Int = Obj.swamppaste

    fun facilityCost(facility: Facility): Cost = cost(facility.wood, facility.planks, facility.nails, facility.bars)

    fun cannonCost(tier: CannonTier): Cost = cost(tier.wood, CannonTier.PLANKS, CannonTier.NAILS, CannonTier.BARS)

    fun hookCost(tier: HookTier): Cost = cost(tier.wood, HookTier.PLANKS, HookTier.NAILS, HookTier.BARS)

    fun bypassFee(tier: Int): Int? = BYPASS_FEES.getOrNull(tier)

    private fun cost(wood: Wood, planks: Int, nails: Int, bars: Int, gems: Int = 0): Cost {
        val items = linkedMapOf<Int, Int>()
        items.merge(PLANK.getValue(wood), planks, Int::plus)
        if (nails > 0) items.merge(NAIL.getValue(wood), nails, Int::plus)
        if (bars > 0) items.merge(BAR.getValue(wood), bars, Int::plus)
        GEM[wood]?.let { if (gems > 0) items.merge(it, gems, Int::plus) }
        return Cost(items)
    }
}
