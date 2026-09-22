package com.rs.game.content.bosses.instance

import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile

/**
 * Everything an instanced boss needs. The arena is described by the regions the encounter occupies
 * rather than by a list of what stands in them: the copy pulls the world's own spawns, and a boss
 * that is a [com.rs.game.content.bosses.MinionHost] brings its minions with it, so nothing here
 * has to name a minion, a position or a respawn.
 */
data class BossInstanceDef(
    val key: String,
    val displayName: String,
    val bossNpc: Int,
    val home: Tile,
    val returnTo: Tile,
    val regions: List<Int> = listOf(home.getRegionId()),
    val entrance: Tile = home,
    val maxPlayers: Int = 10,
    val minCombatDefault: Int = 3,
    val spawnSpeeds: List<SpawnSpeed> = SpawnSpeed.entries,
    val cost: Int = 0,
    val entranceRequirements: String = "None",
) {
    private val origin: Region = Region(regions.minOf { Region(it).x }, regions.minOf { Region(it).y })

    val widthRegions: Int get() = regions.maxOf { Region(it).x } - origin.x + 1
    val heightRegions: Int get() = regions.maxOf { Region(it).y } - origin.y + 1

    /** Where a real-world arena tile lands inside an instance whose base is the arena origin. */
    fun localOffset(tile: Tile): IntArray =
        intArrayOf(tile.x - origin.tile.x, tile.y - origin.tile.y, tile.plane)

    val originChunkX: Int get() = origin.x shl 3
    val originChunkY: Int get() = origin.y shl 3
}

object BossInstanceDefs {
    private val byKey = LinkedHashMap<String, BossInstanceDef>()
    private val byBossNpc = HashMap<Int, BossInstanceDef>()

    val all: List<BossInstanceDef> get() = byKey.values.toList()

    fun register(def: BossInstanceDef) {
        byKey[def.key] = def
        byBossNpc[def.bossNpc] = def
    }

    operator fun get(key: String): BossInstanceDef? = byKey[key]

    fun forBoss(npcId: Int): BossInstanceDef? = byBossNpc[npcId]
}
