package org.darkan.core.map

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.source.codec.WorldGenerator

object GeneratedWorld {

    val windowX: Int = EnvVars.worldWindowX ?: 0
    val windowY: Int = EnvVars.worldWindowY ?: 0

    val generator: WorldGenerator by lazy {
        WorldGenerator(WorldGenerator.DEFAULT_SEED, WorldWindow.origin(windowX), WorldWindow.origin(windowY), EnvVars.generateSize)
    }

    fun onRoad(x: Int, y: Int): Boolean = EnvVars.generateMaps && generator.onRoad(x, y)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = EnvVars.generateMaps && generator.roadTouches(minX, minY, maxX, maxY)
}
