package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.content.bosses.godwars.GodwarsController
import com.rs.game.content.bosses.godwars.factions.zaros.NexArena
import com.rs.game.map.Chunk
import com.rs.game.map.ChunkManager
import com.rs.game.map.instance.Instance
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.utils.Areas
import com.rs.utils.spawns.NPCSpawn
import org.darkan.core.EnvVars
import org.darkan.core.JsonFileManager
import org.darkan.core.Logger
import org.darkan.core.dungeon.GodwarsTemplate
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Rights
import org.darkan.core.util.MapUtils
import org.darkan.core.util.Structure
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

private const val CHUNK_SHIFT = 3
private const val CHUNK_TILES = 8
private const val GROUND_LEVEL = 0
private const val WIDTH_CHUNKS = GodwarsTemplate.WIDTH / CHUNK_TILES
private const val DEPTH_CHUNKS = GodwarsTemplate.DEPTH / CHUNK_TILES
private const val SURFACE_OFFSET_X = 1
private const val SURFACE_OFFSET_Y = -1
private const val ARRIVAL_X = 2881
private const val ARRIVAL_Y = 5310
private const val ARRIVAL_PLANE = 2
private const val NEX_X = 2924
private const val NEX_Y = 5202
private const val CLIMB_TICKS = 2
private const val CHECK_TICKS = 50
private const val EMPTY_CHECKS_BEFORE_CLOSE = 12
private const val MULTI_AREA = "multi"
private val SPAWN_FILES = listOf("./data/npcs/spawns/areas/god_wars_dungeon.json", "./data/npcs/spawns/real_rs_spawns_unorganized.json")
private val ALL_PLANES = intArrayOf(0, 1, 2, 3)

class GodwarsDungeon(val site: WorldGenerator.GodwarsSite) {
    val id = "${site.cellX}_${site.cellY}"
    val surface: Tile get() = Tile.of(site.x + SURFACE_OFFSET_X, site.y + SURFACE_OFFSET_Y, GROUND_LEVEL)

    private var instance: Instance? = null
    private var built = false
    private val waiting = ArrayList<(Boolean) -> Unit>()
    private val npcs = ArrayList<NPC>()
    private var emptyChecks = 0

    var nexArena: NexArena? = null
        private set

    val open: Boolean get() = built

    fun tile(x: Int, y: Int, plane: Int): Tile {
        val base = instance?.takeIf { built }?.tileBase ?: return surface
        return Tile.of(base.x + x - GodwarsTemplate.SOURCE_X, base.y + y - GodwarsTemplate.SOURCE_Y, plane)
    }

    fun sourceX(x: Int): Int = x - (instance?.baseX ?: 0) + GodwarsTemplate.SOURCE_X

    fun sourceY(y: Int): Int = y - (instance?.baseY ?: 0) + GodwarsTemplate.SOURCE_Y

    fun contains(tile: Tile): Boolean {
        val base = instance?.takeIf { built }?.tileBase ?: return false
        return tile.x >= base.x && tile.x < base.x + GodwarsTemplate.WIDTH && tile.y >= base.y && tile.y < base.y + GodwarsTemplate.DEPTH
    }

    fun whenOpen(ready: (Boolean) -> Unit) {
        if (built) return ready(true)
        waiting += ready
        if (instance != null) return
        val startedAt = System.currentTimeMillis()
        val created = Instance.of(surface, WIDTH_CHUNKS, DEPTH_CHUNKS, false, false)
        instance = created
        created.copyMap(ALL_PLANES, GodwarsTemplate.BASE_X shr CHUNK_SHIFT, GodwarsTemplate.BASE_Y shr CHUNK_SHIFT, ALL_PLANES, WIDTH_CHUNKS, DEPTH_CHUNKS)
            .whenComplete { _, failure ->
                val opened = failure == null && runCatching { populate(created, startedAt) }.onFailure { Logger.handle(GodwarsDungeon::class.java, "open", it) }.isSuccess
                if (!opened) {
                    close()
                    instance = null
                }
                waiting.toList().also { waiting.clear() }.forEach { it(opened) }
            }
    }

    private fun populate(created: Instance, startedAt: Long) {
        built = true
        emptyChecks = 0
        markMulticombat(created)
        nexArena = NexArena(tile(NEX_X, NEX_Y, GROUND_LEVEL))
        GodwarsDungeons.spawns.forEach { spawn -> npcs += spawn.spawnAtCoords(tile(spawn.tile.x, spawn.tile.y, spawn.tile.plane), spawn.dir) }
        Logger.info(GodwarsDungeon::class.java, "open", "God Wars Dungeon $id built in ${System.currentTimeMillis() - startedAt}ms with ${npcs.size} npcs")
    }

    private fun markMulticombat(created: Instance) {
        val sourceChunkX = GodwarsTemplate.SOURCE_X shr CHUNK_SHIFT
        val sourceChunkY = GodwarsTemplate.SOURCE_Y shr CHUNK_SHIFT
        for (plane in ALL_PLANES) for (x in 0 until WIDTH_CHUNKS) for (y in 0 until DEPTH_CHUNKS) {
            val multi = Areas.withinArea(MULTI_AREA, MapUtils.encode(Structure.CHUNK, sourceChunkX + x, sourceChunkY + y, plane))
            ChunkManager.getChunk(created.getChunkId(x, y, plane)).setMulticombat(multi)
        }
    }

    fun check() {
        if (!built) return
        val occupied = World.players.any { it.hasStarted() && !it.hasFinished() && contains(it.tile) }
        emptyChecks = if (occupied) 0 else emptyChecks + 1
        if (emptyChecks >= EMPTY_CHECKS_BEFORE_CLOSE) close()
    }

    private fun close() {
        val closing = instance ?: return
        nexArena?.deleteNPCS()
        nexArena = null
        npcs.forEach { it.permanentlyDelete() }
        npcs.clear()
        World.NPCs.filterNotNull().filter { !it.hasFinished() && contains(it.tile) }.forEach { it.permanentlyDelete() }
        built = false
        instance = null
        closing.destroy()
        Logger.info(GodwarsDungeon::class.java, "close", "God Wars Dungeon $id closed after standing empty")
    }
}

object GodwarsDungeons {

    private val dungeons = ConcurrentHashMap<String, GodwarsDungeon>()

    val spawns: List<NPCSpawn> by lazy {
        SPAWN_FILES.flatMap { JsonFileManager.loadJsonFile(File(it), Array<NPCSpawn>::class.java).orEmpty().filterNotNull() }.filter {
            it.tile.x - GodwarsTemplate.SOURCE_X in 0 until GodwarsTemplate.WIDTH && it.tile.y - GodwarsTemplate.SOURCE_Y in 0 until GodwarsTemplate.DEPTH
        }
    }

    private val byChunk: Map<Int, WorldGenerator.GodwarsSite> by lazy {
        GeneratedWorld.godwarsSites.associateBy { chunkKey(it.x shr CHUNK_SHIFT, it.y shr CHUNK_SHIFT) }
    }

    private fun chunkKey(chunkX: Int, chunkY: Int) = chunkX shl Short.SIZE_BITS or chunkY

    private fun of(site: WorldGenerator.GodwarsSite): GodwarsDungeon = dungeons.computeIfAbsent("${site.cellX}_${site.cellY}") { GodwarsDungeon(site) }

    @JvmStatic
    fun byId(id: String): GodwarsDungeon? =
        dungeons[id] ?: GeneratedWorld.godwarsSites.firstOrNull { "${it.cellX}_${it.cellY}" == id }?.let(::of)

    @JvmStatic
    fun entranceAt(x: Int, y: Int): GodwarsDungeon? = if (EnvVars.generateMaps) GeneratedWorld.godwarsAt(x, y)?.let(::of) else null

    @JvmStatic
    fun covers(x: Int, y: Int): Boolean = GeneratedWorld.godwarsAt(x, y, WorldGenerator.GODWARS_MARGIN) != null

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND_LEVEL) return
        val site = byChunk[chunkKey(chunk.x, chunk.y)] ?: return
        chunk.addGeneratedObject(GameObject(Loc.loc_26342, ObjectShape.SCENERY_INTERACT, 0, site.x, site.y, GROUND_LEVEL))
    }

    fun enter(player: Player, dungeon: GodwarsDungeon) {
        player.lock()
        player.anim(Seq.human_reachforladder)
        WorldTasks.schedule(CLIMB_TICKS) {
            dungeon.whenOpen { opened ->
                if (player.hasFinished()) return@whenOpen
                if (!opened) {
                    player.unlock()
                    player.sendMessage("The rope will not hold. Try again in a moment.")
                    return@whenOpen
                }
                player.tele(dungeon.tile(ARRIVAL_X, ARRIVAL_Y, ARRIVAL_PLANE))
                player.controllerManager.startController(GodwarsController(dungeon.id))
                player.unlock()
            }
        }
    }

    fun checkAll() = dungeons.values.forEach { it.check() }

    fun sites(): List<WorldGenerator.GodwarsSite> = GeneratedWorld.godwarsSites
}

@ServerStartupEvent
fun mapGodwarsDungeons() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(CHECK_TICKS, CHECK_TICKS) {
        GodwarsDungeons.checkAll()
        true
    }
    CompletableFuture.supplyAsync { GodwarsDungeons.sites() }.thenAccept { sites ->
        Logger.info(GodwarsDungeons::class.java, "sites", "God Wars Dungeon entrances (${sites.size}): " + sites.joinToString { "${it.x},${it.y}" })
    }
    Commands.add(Rights.PLAYER, "gwd [number]", "Lists the God Wars Dungeon entrances, or teleports to one. Debug worlds only.") { player, args ->
        if (!Settings.getConfig().isDebug) return@add
        val sites = GodwarsDungeons.sites()
        val site = args.firstOrNull()?.toIntOrNull()?.let { sites.getOrNull(it) }
        if (site == null) player.sendMessage("God Wars Dungeons (${sites.size}): " + sites.withIndex().joinToString { (index, hole) -> "$index at ${hole.x},${hole.y}" })
        else player.tele(Tile.of(site.x + SURFACE_OFFSET_X, site.y + SURFACE_OFFSET_Y, GROUND_LEVEL))
    }
}
