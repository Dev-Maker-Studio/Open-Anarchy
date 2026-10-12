package com.rs.game.content.bosses.instance

import com.rs.game.model.entity.player.Controller
import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile

data class BossInstanceDef(
    val key: String,
    val displayName: String,
    val bossNpcs: List<Int>,
    val home: Tile,
    val returnTo: Tile,
    val regions: List<Int> = listOf(home.getRegionId()),
    val planes: List<Int> = listOf(home.plane),
    val entrance: Tile = home,
    val maxPlayers: Int = 10,
    val minCombatDefault: Int = 3,
    val spawnSpeeds: List<SpawnSpeed> = SpawnSpeed.entries,
    val cost: Int = 0,
    val portraitZoom: Int = 0,
    val exitLoc: Int = -1,
    val exitTile: Tile? = null,
    val instanceExitLoc: Int = -1,
    val entranceRequirements: String = "None",
    val insideVarbit: Int = -1,
    val returnController: (() -> Controller)? = null,
) {
    val portraitNpc: Int get() = bossNpcs.first()

    private val origin: Region = Region(regions.minOf { Region(it).x }, regions.minOf { Region(it).y })

    val widthRegions: Int get() = regions.maxOf { Region(it).x } - origin.x + 1
    val heightRegions: Int get() = regions.maxOf { Region(it).y } - origin.y + 1

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
        def.bossNpcs.forEach { byBossNpc[it] = def }
    }

    operator fun get(key: String): BossInstanceDef? = byKey[key]

    fun forBoss(npcId: Int): BossInstanceDef? = byBossNpc[npcId]
}
