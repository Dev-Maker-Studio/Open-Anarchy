package com.rs.game.content.skills.mining

import com.rs.game.World
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import com.rs.utils.Ticks
import org.darkan.core.Constants
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import kotlin.math.abs

private const val REPLENISH_MINUTES = 5
private const val GATHER_XP = 17.5
private const val CLUSTER_RADIUS = 3
private const val ROCKS_PER_CLUSTER = 6
private const val SPAWN_POLL_START_TICKS = 5
private const val SPAWN_POLL_TICKS = 5
private const val SPAWN_POLL_MAX_ATTEMPTS = 240

private enum class DesertRock(val loc: Int, val ore: Int) {
    COPPER(Loc.desert_gather_copper_rocks, Obj.copper_ore),
    TIN(Loc.desert_gather_tin_rocks, Obj.tin_ore),
}

private val CLUSTER_CENTRES = listOf(
    Tile.of(3315, 3160, 0),
    Tile.of(3350, 3170, 0),
    Tile.of(3290, 3100, 0),
)

@ServerStartupEvent(ServerStartupEvent.Priority.POST_PROCESS)
fun mapDesertRocks() {
    onObjectClick(*DesertRock.entries.map { it.loc }.toTypedArray()) { (player, obj) -> gather(player, obj) }
    ChunkManager.permanentlyPreloadRegions(CLUSTER_CENTRES.map { it.getRegionId() }.toSet())
    val pending = CLUSTER_CENTRES.toMutableList()
    WorldTasks.scheduleTimer(SPAWN_POLL_START_TICKS, SPAWN_POLL_TICKS) { attempt ->
        pending.removeAll { centre ->
            val loaded = ChunkManager.getChunk(centre.getChunkId(), false).isLoadedMapData
            if (loaded) spawnCluster(centre)
            loaded
        }
        pending.isNotEmpty() && attempt < SPAWN_POLL_MAX_ATTEMPTS
    }
}

private fun spawnCluster(centre: Tile) {
    var placed = 0
    for (radius in 0..CLUSTER_RADIUS) {
        for (dx in -radius..radius) for (dy in -radius..radius) {
            if (placed == ROCKS_PER_CLUSTER) return
            if (maxOf(abs(dx), abs(dy)) != radius) continue
            val tile = centre.transform(dx, dy, 0)
            if (!World.floorAndWallsFree(tile, 1) || World.getObject(tile) != null) continue
            val rock = DesertRock.entries[placed % DesertRock.entries.size]
            World.spawnObject(GameObject(rock.loc, ObjectShape.SCENERY_INTERACT, 0, tile))
            placed++
        }
    }
}

private fun gather(player: Player, obj: GameObject) {
    val rock = DesertRock.entries.first { it.loc == obj.id }
    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("Your inventory is too full to gather any more ore.")
        return
    }
    player.inventory.addItem(rock.ore)
    player.skills.addXp(Constants.MINING, GATHER_XP)
    player.sendMessage("You gather some ${ItemDefinitions.getDefs(rock.ore).name.lowercase()}.")
    obj.setIdTemporary(Loc.desert_gather_rocks_depleted, Ticks.fromMinutes(REPLENISH_MINUTES))
}
