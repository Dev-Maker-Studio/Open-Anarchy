package com.rs.game.content.world

import com.rs.game.World
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.EnvVars
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.gameval.gen.Varp
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile

private const val SLOTS = 24
private const val REFRESH_TICKS = 3
private const val GROUND_PLANE = 0
private const val GREEN_GROUND = 0
private const val DESERT_GROUND = 1
private const val SNOW_GROUND = 2
private const val ADDED = 3
private const val NO_MAP_SCENE = -1
private val MAP_RANGE = WorldWindow.MAP_ORIGIN until WorldWindow.MAP_ORIGIN + WorldWindow.MAP_TILES

private val OBJECT_VARPS = IntArray(SLOTS) { Varp.worldmap_object_0 + it }

@ServerStartupEvent
fun mapWorldMapObjects() {
    if (!EnvVars.generateMaps) return
    onLogin { (player) -> pushMapObjects(player, visibleChanges()) }
    WorldTasks.scheduleTimer(REFRESH_TICKS, REFRESH_TICKS) {
        val changes = visibleChanges()
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished()) pushMapObjects(player, changes)
        }
        true
    }
}

private fun visibleChanges(): List<WorldDelta.MapChange> =
    WorldDelta.mapChanges().filter { it.x in MAP_RANGE && it.y in MAP_RANGE && ObjectDefinitions.getDefs(it.id).mapscene != NO_MAP_SCENE }

private fun pushMapObjects(player: Player, changes: List<WorldDelta.MapChange>) {
    val nearest = changes.sortedBy { distance(it, player) }.take(SLOTS)
    for (slot in 0 until SLOTS) {
        val value = nearest.getOrNull(slot)?.let { Tile.of(it.x, it.y, state(it)).id } ?: 0
        if (player.vars.getVar(OBJECT_VARPS[slot]) != value) player.vars.setVar(OBJECT_VARPS[slot], value)
    }
}

private fun state(change: WorldDelta.MapChange): Int {
    if (change.added) return ADDED
    return when (GeneratedWorld.generator.biomeAt(change.x, change.y)) {
        WorldGenerator.Biome.DESERT -> DESERT_GROUND
        WorldGenerator.Biome.WINTER -> SNOW_GROUND
        else -> GREEN_GROUND
    }
}

private fun distance(change: WorldDelta.MapChange, player: Player): Long {
    val dx = (change.x - player.x).toLong()
    val dy = (change.y - player.y).toLong()
    return dx * dx + dy * dy
}
