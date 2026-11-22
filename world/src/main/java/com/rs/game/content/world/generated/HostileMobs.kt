package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.map.ChunkManager
import com.rs.game.map.instance.InstancedChunk
import com.rs.game.model.entity.npc.NPC
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.util.MapUtils
import org.darkan.core.util.Structure
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

private const val SPAWN_INTERVAL_TICKS = 25
private const val SPAWN_CHANCE_PERCENT = 1.0
private const val WILDERNESS_SPAWN_MULTIPLIER = 1.5
private const val WILDERNESS_LEVEL_MULTIPLIER = 1.2
private const val LOADED_CHUNK_RADIUS = 6
private const val CHUNK_TILES = 8
private const val PLAYER_CLEARANCE = 18
private const val PLACEMENT_ATTEMPTS = 8
private const val DESPAWN_DISTANCE = 96
private const val GROUND_LEVEL = 0
private const val PERCENT = 100.0

private val generator = GeneratedWorld.generator

private val hostileIds = mapOf(
    Biome.PLAINS to intArrayOf(Npc.spider, Npc.rand_giant_rat_1, Npc.rand_hill_giant_1, Npc.darkbear, Npc.brownbear, Npc.rand_hill_giant_2, Npc.wolf),
    Biome.FOREST to intArrayOf(Npc.spider, Npc.giantspider1, Npc.darkbear, Npc.brownbear, Npc.giantspider2, Npc.mossgiant, Npc.wolf),
    Biome.DESERT to intArrayOf(
        Npc.spider, Npc.snake, Npc.scorpion, Npc.poison_scorpion, Npc.ics_little_jackal, Npc.kingscorpion, Npc.npc_2803, Npc.hellhound
    ),
    Biome.WILDERNESS to intArrayOf(
        Npc.rand_zombie_ranged_1, Npc.rand_ghost_1, Npc.rand_skeleton_melee_lvl1_1, Npc.ghost, Npc.zombie_armed, Npc.skeleton_armed,
        Npc.rand_fire_giant_1, Npc.mossgiant, Npc.rand_lesser_demon_1, Npc.firegiant, Npc.hellhound
    ),
    Biome.WINTER to intArrayOf(
        Npc.rand_bat_1, Npc.darkbear, Npc.whitewolf, Npc.icegiant, Npc.icewarrior, Npc.rand_ice_troll_melee_1, Npc.ice_spider, Npc.wolf
    ),
    Biome.MOUNTAIN to intArrayOf(
        Npc.rand_bat_1, Npc.rand_earth_warrior_1, *HILL_GIANT_IDS, Npc.darkbear, Npc.bat, Npc.mossgiant, Npc.babybluedragon,
        Npc.ogre2, Npc.wolf, Npc.troll
    ),
    Biome.VOLCANO to intArrayOf(
        Npc.imp, Npc.elemental_fire, Npc.rand_fire_giant_1, Npc.babyreddragon, Npc.rand_lesser_demon_1, Npc.rand_hellhound_1,
        Npc.rand_fire_giant_2, Npc.firegiant, Npc.hellhound
    ),
    Biome.OCEAN to intArrayOf(Npc.rand_bat_1, Npc.small_bat, Npc.bat),
    Biome.RUNE_ESSENCE to intArrayOf(Npc.whitewolf_sentry, Npc.pack_wolf_whiter, Npc.whitewolf, Npc.wolfpack_leader_whiter)
)

private val byLevel: Map<Biome, List<Int>> by lazy {
    hostileIds.mapValues { (_, ids) -> ids.sortedBy { NPCDefinitions.getDefs(it).combatLevel } }
}

private val spawned = ConcurrentHashMap.newKeySet<NPC>()

@ServerStartupEvent
fun startHostileMobs() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(SPAWN_INTERVAL_TICKS, SPAWN_INTERVAL_TICKS) {
        despawnAbandoned()
        loadedChunks().forEach { chunkId -> if (rollSpawn(chunkId)) spawnInChunk(chunkId) }
        true
    }
}

fun hostileIdsFor(biome: Biome, level: Int): List<Int> =
    byLevel[biome].orEmpty().filter { NPCDefinitions.getDefs(it).combatLevel <= level }

fun hostileIdsAt(biome: Biome, x: Int, y: Int): List<Int> =
    SpawnAreas.ids(biome.name, x, y)?.asList() ?: SpawnAreas.kept(biome.name, hostileIdsFor(biome, threatLevel(x, y)))

private fun rollSpawn(chunkId: Int): Boolean {
    val (chunkX, chunkY, _) = MapUtils.decode(Structure.CHUNK, chunkId)
    val multiplier = if (generator.biomeAt(chunkX * CHUNK_TILES, chunkY * CHUNK_TILES) == Biome.WILDERNESS) WILDERNESS_SPAWN_MULTIPLIER else 1.0
    return Math.random() * PERCENT < SPAWN_CHANCE_PERCENT * multiplier
}

private fun loadedChunks(): List<Int> = World.players
    .filter { !it.isBot && it.hasStarted() && !it.hasFinished() && it.plane == GROUND_LEVEL && !GeneratedWorld.inDungeon(it.x, it.y) }
    .flatMap { World.getChunkRadius(it.chunkId, LOADED_CHUNK_RADIUS) }
    .distinct()
    .filter { ChunkManager.findChunk(it).let { chunk -> chunk != null && chunk !is InstancedChunk } }

private fun spawnInChunk(chunkId: Int) {
    val (chunkX, chunkY, plane) = MapUtils.decode(Structure.CHUNK, chunkId)
    if (plane != GROUND_LEVEL) return
    repeat(PLACEMENT_ATTEMPTS) {
        val tile = Tile.of(chunkX * CHUNK_TILES + Utils.random(CHUNK_TILES), chunkY * CHUNK_TILES + Utils.random(CHUNK_TILES), plane)
        if (!canSpawnAt(tile)) return@repeat
        spawnHostile(tile)
        return
    }
}

private fun canSpawnAt(tile: Tile): Boolean =
    World.floorAndWallsFree(tile, 1) &&
        generator.liquidTileAt(tile.x, tile.y) == null &&
        !GeneratedWorld.onRoad(tile.x, tile.y) &&
        !GeneratedWorld.inCity(tile.x, tile.y) &&
        !GeneratedWorld.inDungeon(tile.x, tile.y) &&
        World.players.none { !it.hasFinished() && it.plane == tile.plane && Utils.getDistance(it.tile, tile) < PLAYER_CLEARANCE }

private fun spawnHostile(tile: Tile) {
    val biome = generator.biomeAt(tile.x, tile.y)
    val level = threatLevel(tile)
    val id = hostileIdsAt(biome, tile.x, tile.y).randomOrNull() ?: return
    spawned.add(scaledHostile(id, tile, biome, level))
}

fun hostileScale(id: Int, biome: Biome, level: Int): Double {
    val native = NPCDefinitions.getDefs(id).combatLevel.coerceAtLeast(1)
    return maxOf(1.0, level.toDouble() / native) * if (biome == Biome.WILDERNESS) WILDERNESS_LEVEL_MULTIPLIER else 1.0
}

fun scaledHostile(id: Int, tile: Tile, biome: Biome, level: Int = threatLevel(tile)): HostileNPC {
    val scale = hostileScale(id, biome, level)
    return HostileNPC(id, tile, scale, (NPCDefinitions.getDefs(id).combatLevel.coerceAtLeast(1) * scale).toInt())
}

fun withinLevel(id: Int, level: Int): Boolean = NPCDefinitions.getDefs(id).combatLevel <= level

private fun despawnAbandoned() {
    spawned.removeIf { mob ->
        val gone = mob.hasFinished() || mob.isDead
        val abandoned = !gone && World.players.none { !it.hasFinished() && it.plane == mob.plane && Utils.getDistance(it.tile, mob.tile) < DESPAWN_DISTANCE }
        if (abandoned) mob.finish()
        gone || abandoned
    }
}
