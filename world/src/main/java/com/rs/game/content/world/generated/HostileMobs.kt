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
    Biome.PLAINS to intArrayOf(Npc.rand_giant_rat_1, Npc.spider, Npc.wolf, Npc.brownbear, Npc.darkbear),
    Biome.FOREST to intArrayOf(Npc.spider, Npc.giantspider1, Npc.wolf, Npc.mossgiant, Npc.brownbear, Npc.darkbear),
    Biome.DESERT to intArrayOf(Npc.scorpion, Npc.snake, Npc.poison_scorpion, Npc.kingscorpion, Npc.hellhound),
    Biome.WILDERNESS to intArrayOf(Npc.skeleton_armed, Npc.zombie_armed, Npc.ghost, Npc.mossgiant, Npc.hellhound, Npc.firegiant),
    Biome.WINTER to intArrayOf(Npc.wolf, Npc.icewarrior, Npc.icegiant, Npc.darkbear, Npc.icegiant2),
    Biome.MOUNTAIN to intArrayOf(Npc.bat, Npc.wolf, Npc.ogre, Npc.mossgiant, Npc.darkbear, Npc.babybluedragon),
    Biome.VOLCANO to intArrayOf(Npc.imp, Npc.hellhound, Npc.babyreddragon, Npc.firegiant),
    Biome.OCEAN to intArrayOf(Npc.small_bat, Npc.bat)
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

fun hostileIdsFor(biome: Biome, level: Int): List<Int> {
    val ordered = byLevel[biome] ?: return emptyList()
    val allowed = ordered.filter { NPCDefinitions.getDefs(it).combatLevel <= level }
    return allowed.ifEmpty { listOf(ordered.first()) }
}

private fun rollSpawn(chunkId: Int): Boolean {
    val (chunkX, chunkY, _) = MapUtils.decode(Structure.CHUNK, chunkId)
    val multiplier = if (generator.biomeAt(chunkX * CHUNK_TILES, chunkY * CHUNK_TILES) == Biome.WILDERNESS) WILDERNESS_SPAWN_MULTIPLIER else 1.0
    return Math.random() * PERCENT < SPAWN_CHANCE_PERCENT * multiplier
}

private fun loadedChunks(): List<Int> = World.players
    .filter { !it.isBot && it.hasStarted() && !it.hasFinished() && it.plane == GROUND_LEVEL }
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
        World.players.none { !it.hasFinished() && it.plane == tile.plane && Utils.getDistance(it.tile, tile) < PLAYER_CLEARANCE }

private fun spawnHostile(tile: Tile) {
    val biome = generator.biomeAt(tile.x, tile.y)
    val wilderness = biome == Biome.WILDERNESS
    val baseLevel = threatLevel(tile)
    val level = if (wilderness) (baseLevel * WILDERNESS_LEVEL_MULTIPLIER).toInt().coerceAtLeast(baseLevel) else baseLevel
    val ids = hostileIdsFor(biome, level)
    if (ids.isEmpty()) return
    val id = ids.random()
    val native = NPCDefinitions.getDefs(id).combatLevel.coerceAtLeast(1)
    val scale = maxOf(1.0, level.toDouble() / native) * if (wilderness) WILDERNESS_LEVEL_MULTIPLIER else 1.0
    val mob = HostileNPC(id, tile, scale, (native * scale).toInt())
    spawned.add(mob)
}

private fun despawnAbandoned() {
    spawned.removeIf { mob ->
        val gone = mob.hasFinished() || mob.isDead
        val abandoned = !gone && World.players.none { !it.hasFinished() && it.plane == mob.plane && Utils.getDistance(it.tile, mob.tile) < DESPAWN_DISTANCE }
        if (abandoned) mob.finish()
        gone || abandoned
    }
}
