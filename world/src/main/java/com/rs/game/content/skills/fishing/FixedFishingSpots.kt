package com.rs.game.content.skills.fishing

import com.rs.game.map.Chunk
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.npc.NPC
import com.rs.game.tasks.WorldTasks
import com.rs.utils.Ticks
import org.darkan.core.EnvVars
import world.gregs.voidps.cache.source.codec.WorldGenerator.FishingSite
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

private const val CATCHES_PER_SPOT = 3
private const val GROUND_LEVEL = 0
private const val CHUNK_SHIFT = 3
private val RESPAWN_TICKS = Ticks.fromMinutes(5)

class FixedFishingSpot(val site: FishingSite) : NPC(site.kind.npc, Tile.of(site.x, site.y, GROUND_LEVEL), null, true) {

    private var catches = 0

    init {
        setRandomWalk(false)
    }

    fun caught(): Boolean = ++catches >= CATCHES_PER_SPOT
}

object FixedFishingSpots {

    private val managed = ConcurrentHashMap.newKeySet<Long>()

    private fun key(x: Int, y: Int) = x.toLong() shl Int.SIZE_BITS or (y.toLong() and 0xFFFFFFFFL)

    @JvmStatic
    fun spawn(chunk: Chunk, sites: List<FishingSite>) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND_LEVEL) return
        for (site in sites) {
            if (site.x shr CHUNK_SHIFT != chunk.x || site.y shr CHUNK_SHIFT != chunk.y) continue
            if (!managed.add(key(site.x, site.y))) continue
            val remaining = WorldDelta.spotRegrowTicks(site.x, site.y, GROUND_LEVEL)
            if (remaining > 0) WorldTasks.schedule(remaining) { place(site) } else place(site)
        }
    }

    private fun place(site: FishingSite) {
        WorldDelta.spotRestored(site.x, site.y, GROUND_LEVEL)
        FixedFishingSpot(site)
    }

    fun deplete(spot: FixedFishingSpot) {
        spot.finish()
        val site = spot.site
        WorldDelta.spotDepleted(site.x, site.y, GROUND_LEVEL, RESPAWN_TICKS)
        WorldTasks.schedule(RESPAWN_TICKS) { place(site) }
    }
}
