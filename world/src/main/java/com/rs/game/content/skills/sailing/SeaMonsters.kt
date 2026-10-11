package com.rs.game.content.skills.sailing

import com.rs.game.World
import com.rs.game.content.world.generated.HostileNPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import world.gregs.voidps.type.Tile

private const val GROUND_LEVEL = 0
private const val AGGRO_TILES = 40
private const val ATTACK_REACH_EXTRA = 2
private const val ATTACK_TICKS = 5
private const val MOVE_EVERY_TICKS = 2
private const val BASE_BOAT_HIT = 2
private const val LEVEL_PER_BOAT_HIT = 12
private const val MAX_BOAT_HIT = 22
private const val LOOT_ROLLS_PER_THREAT = 8
private const val CASKET_PERCENT = 6
private const val MIN_BOAT_HIT = 1

private val SPECIES = listOf(
    Npc.giant_lobster, Npc.slug2_giant_lobster, Npc.swan_troll_battle, Npc.royal_sea_snake_medium, Npc.royal_sea_snake_mother_smaller
)

private val FISH_BY_TIER = listOf(Obj.raw_tuna, Obj.raw_swordfish, Obj.raw_lobster, Obj.raw_shark)

object SeaMonsterTable {
    private val byLevel: List<Int> by lazy { SPECIES.sortedBy { NPCDefinitions.getDefs(it).combatLevel } }

    fun idsFor(level: Int): List<Int> {
        val allowed = byLevel.filter { NPCDefinitions.getDefs(it).combatLevel <= level }
        return allowed.ifEmpty { listOf(byLevel.first()) }
    }

    fun spawn(tile: Tile, threat: Int): SeaMonster {
        val id = idsFor(threat).random()
        val native = NPCDefinitions.getDefs(id).combatLevel.coerceAtLeast(1)
        val scale = maxOf(1.0, threat.toDouble() / native)
        return SeaMonster(id, tile, scale, (native * scale).toInt())
    }
}

class SeaMonster(id: Int, tile: Tile, scale: Double, val monsterLevel: Int) : HostileNPC(id, tile, scale, monsterLevel) {
    var voyage: Voyage? = null
    private var nextAttack = 0L

    override fun processNPC() {
        if (isDead || isLocked) return
        val ship = voyage?.takeIf { it.boat.state == BoatState.SAILING } ?: return
        val distance = maxOf(kotlin.math.abs(ship.tileX - x), kotlin.math.abs(ship.tileY - y))
        val reach = ship.boat.type.length / 2 + ATTACK_REACH_EXTRA
        if (distance > AGGRO_TILES) {
            finish()
            return
        }
        setNextFaceTile(Tile.of(ship.tileX, ship.tileY, GROUND_LEVEL))
        if (distance > reach) {
            if (World.getServerTicks() % MOVE_EVERY_TICKS == 0L) approach(ship)
            return
        }
        if (World.getServerTicks() < nextAttack) return
        nextAttack = World.getServerTicks() + ATTACK_TICKS
        anim(combatDefinitions.attackEmote)
        Voyages.hitBoat(ship, MIN_BOAT_HIT + Utils.random(boatHit()), name)
    }

    override fun checkAggressivity(): Boolean = false

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun randomWalk() {}

    override fun drop() {
        val ship = voyage ?: return
        val threat = PortSites.levelAt(x, y)
        val rolls = 1 + threat / LOOT_ROLLS_PER_THREAT
        repeat(rolls) {
            val tier = (monsterLevel / TIER_LEVELS).coerceAtMost(FISH_BY_TIER.size - 1)
            ship.boat.hold.addGood(FISH_BY_TIER[tier], 1)
        }
        if (Utils.random(100) < CASKET_PERCENT) ship.boat.hold.addGood(Obj.casket, 1)
        World.getPlayerByUsername(ship.username)?.sendMessage("You haul the $name's remains into your cargo hold.")
    }

    private fun boatHit(): Int = (BASE_BOAT_HIT + monsterLevel / LEVEL_PER_BOAT_HIT).coerceAtMost(MAX_BOAT_HIT)

    private fun approach(ship: Voyage) {
        val stepX = Integer.signum(ship.tileX - x)
        val stepY = Integer.signum(ship.tileY - y)
        val options = listOf(x + stepX to y + stepY, x + stepX to y, x to y + stepY)
        val next = options.firstOrNull { (nx, ny) -> (nx != x || ny != y) && PortSites.sailable(nx, ny) } ?: return
        tele(Tile.of(next.first, next.second, GROUND_LEVEL))
    }

    private companion object {
        const val TIER_LEVELS = 40
    }
}
