package com.rs.game.content.skills.sailing

import kotlin.math.abs
import kotlin.math.max

enum class BagTier(val minBaseXp: Int) {
    TINY(0), SMALL(400), MEDIUM(1_000), LARGE(2_500), HUGE(6_000);

    companion object {
        fun forXp(baseXp: Int): BagTier = entries.last { baseXp >= it.minBaseXp }
    }
}

enum class TaskKind { COURIER, BOUNTY }

class Port(val name: String, val x: Int, val y: Int, val sailingLevel: Int, val threatLevel: Int)

class PortTask(
    val kind: TaskKind,
    val from: Port,
    val to: Port,
    val crates: Int,
    val baseXp: Int,
    val coins: Int
) {
    val bag: BagTier get() = BagTier.forXp(baseXp)
}

object PortTasks {
    private val SLOTS_AT_LEVEL = listOf(1 to 1, 7 to 2, 28 to 3, 56 to 4, 84 to 5)
    private const val XP_PER_TILE = 1.5
    private const val MIN_XP = 40
    private const val COINS_PER_XP = 0.8
    private const val LEVEL_COIN_STEP = 0.02
    private const val BOUNTY_LEVEL = 30
    private const val RANGE_BASE = 150
    private const val RANGE_PER_LEVEL = 10

    fun slots(sailingLevel: Int): Int = SLOTS_AT_LEVEL.lastOrNull { sailingLevel >= it.first }?.second ?: 0

    fun canTakeBounty(sailingLevel: Int) = sailingLevel >= BOUNTY_LEVEL

    fun maxRange(sailingLevel: Int): Int = RANGE_BASE + sailingLevel * RANGE_PER_LEVEL

    fun distance(a: Port, b: Port): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    fun courier(from: Port, to: Port, crates: Int): PortTask {
        val xp = max(MIN_XP, (distance(from, to) * XP_PER_TILE).toInt()) * crates
        val coins = (xp * COINS_PER_XP * (1.0 + to.threatLevel * LEVEL_COIN_STEP)).toInt()
        return PortTask(TaskKind.COURIER, from, to, crates, xp, coins)
    }

    fun eligibleDestinations(from: Port, ports: List<Port>, sailingLevel: Int): List<Port> =
        ports.filter { it !== from && it.sailingLevel <= sailingLevel && distance(from, it) <= maxRange(sailingLevel) }
}
