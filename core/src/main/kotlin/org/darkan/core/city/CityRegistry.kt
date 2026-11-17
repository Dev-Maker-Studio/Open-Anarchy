package org.darkan.core.city

import org.darkan.core.Logger.logInfo
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.io.File

object CityRegistry {
    const val DIRECTORY = "./data/cities"

    fun load(plain: () -> WorldGenerator, spawnX: Int, spawnY: Int): List<City> {
        val directory = File(DIRECTORY)
        val saved = CityJson.readAll(directory)
        if (saved.isNotEmpty()) return saved
        val planned = CityPlanner.plan(plain(), spawnX, spawnY, WorldWindow.MAP_ORIGIN, WorldWindow.MAP_ORIGIN + WorldWindow.MAP_TILES)
        planned.forEach { CityJson.write(directory, it) }
        logInfo("Planned ${planned.size} cities: ${planned.joinToString { "${it.name} at ${it.centerX},${it.centerY}" }}")
        return planned
    }
}
