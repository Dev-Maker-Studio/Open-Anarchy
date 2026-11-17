package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.game.content.skills.dungeoneering.DungeonManager
import com.rs.game.map.Chunk
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Rights
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture

private const val CHUNK_SHIFT = 3
private const val GROUND_LEVEL = 0
private const val PARTY_REACH = 12
private const val NPC_OFFSET_Y = -2
private const val MASTER_OFFSET_X = 0
private const val MERCHANT_OFFSET_X = WorldGenerator.CAVE_SIZE - 1
private const val SPAWN_CHECK_TICKS = 10
private const val SPAWN_EVERY_TICK = 1
private const val NAME_SEPARATOR = " "

class DungeonCaveNPC(id: Int, tile: Tile) : CityResident(id, tile)

object DungeoneeringCaves {

    private val byChunk: Map<Int, Pair<Int, Int>> by lazy {
        GeneratedWorld.caveSites.associateBy { (x, y) -> chunkKey(x shr CHUNK_SHIFT, y shr CHUNK_SHIFT) }
    }

    private fun chunkKey(chunkX: Int, chunkY: Int) = chunkX shl Short.SIZE_BITS or chunkY

    @JvmStatic
    fun active(): Boolean = EnvVars.generateMaps

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND_LEVEL) return
        val (x, y) = byChunk[chunkKey(chunk.x, chunk.y)] ?: return
        chunk.addGeneratedObject(GameObject(Loc.activity_cave_entrance, ObjectShape.SCENERY_INTERACT, 0, x, y, GROUND_LEVEL))
    }

    @JvmStatic
    fun covers(x: Int, y: Int): Boolean = GeneratedWorld.caveAt(x, y, WorldGenerator.CAVE_MARGIN) != null

    @JvmStatic
    fun near(tile: Tile): Boolean = EnvVars.generateMaps && tile.plane == GROUND_LEVEL && GeneratedWorld.caveAt(tile.x, tile.y, PARTY_REACH) != null

    fun surface(site: Pair<Int, Int>): Tile = Tile.of(site.first + 1, site.second + NPC_OFFSET_Y, GROUND_LEVEL)
}

@ServerStartupEvent
fun startDungeoneeringCaves() {
    if (!EnvVars.generateMaps) return
    onObjectClick(Loc.activity_cave_entrance) { e ->
        if (GeneratedWorld.caveAt(e.obj.x, e.obj.y) != null) e.player.dungManager.enterDungeon(true)
    }
    onLogin { e ->
        if (e.player.dungManager.rejoinKey != null) DungeonManager.checkRejoin(e.player)
    }
    Commands.add(Rights.PLAYER, "dunginvite [name]", "Opens the dungeon party invitation a player sent you.") { player, args ->
        player.dungManager.invite(args.joinToString(NAME_SEPARATOR))
    }
    Commands.add(Rights.PLAYER, "dungcave [number]", "Lists the dungeoneering caves, or teleports to one. Debug worlds only.") { player, args ->
        if (!Settings.getConfig().isDebug) return@add
        val sites = GeneratedWorld.caveSites
        val site = args.firstOrNull()?.toIntOrNull()?.let { sites.getOrNull(it) }
        if (site == null) player.sendMessage("Dungeoneering caves: " + sites.withIndex().joinToString { (index, cave) -> "$index at ${cave.first},${cave.second}" })
        else player.tele(DungeoneeringCaves.surface(site))
    }
    val sites = CompletableFuture.supplyAsync { GeneratedWorld.caveSites }
    WorldTasks.scheduleTimer(SPAWN_CHECK_TICKS, SPAWN_EVERY_TICK) {
        if (!sites.isDone) return@scheduleTimer true
        for ((x, y) in sites.get()) {
            DungeonCaveNPC(Npc.rand_skillcape_npc, Tile.of(x + MASTER_OFFSET_X, y + NPC_OFFSET_Y, GROUND_LEVEL))
            DungeonCaveNPC(Npc.npc_9711, Tile.of(x + MERCHANT_OFFSET_X, y + NPC_OFFSET_Y, GROUND_LEVEL))
        }
        Logger.info(DungeoneeringCaves::class.java, "spawn", "Dungeoneering caves: " + sites.get().joinToString { (x, y) -> "$x,$y" })
        false
    }
}
