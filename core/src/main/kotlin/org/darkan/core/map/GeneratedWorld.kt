package org.darkan.core.map

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.source.codec.WorldGenerator

object GeneratedWorld {

    val windowX: Int = EnvVars.worldWindowX ?: 0
    val windowY: Int = EnvVars.worldWindowY ?: 0

    val generator: WorldGenerator by lazy {
        WorldGenerator(WorldGenerator.DEFAULT_SEED, WorldWindow.origin(windowX), WorldWindow.origin(windowY), EnvVars.generateSize)
    }

    val spawnX: Int = WorldWindow.toLocal(WorldGenerator.SPAWN_X, windowX).coerceIn(0, WorldWindow.WINDOW_TILES - 1)
    val spawnY: Int = WorldWindow.toLocal(WorldGenerator.SPAWN_Y, windowY).coerceIn(0, WorldWindow.WINDOW_TILES - 1)

    val lodestoneSites: List<Pair<Int, Int>> by lazy { if (EnvVars.generateMaps) generator.lodestoneSites() else emptyList() }

    fun lodestoneZoneTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        lodestoneSites.any { (x, y) ->
            maxX >= x - WorldGenerator.LODESTONE_RADIUS && minX <= x + WorldGenerator.LODESTONE_RADIUS &&
                maxY >= y - WorldGenerator.LODESTONE_RADIUS && minY <= y + WorldGenerator.LODESTONE_RADIUS
        }

    fun inLodestoneZone(x: Int, y: Int): Boolean = lodestoneZoneTouches(x, y, x, y)

    fun onRoad(x: Int, y: Int): Boolean = EnvVars.generateMaps && generator.onRoad(x, y)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = EnvVars.generateMaps && generator.roadTouches(minX, minY, maxX, maxY)
}
