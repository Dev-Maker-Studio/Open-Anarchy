package org.darkan.core.city

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.io.File

object CityRegistry {
    const val DIRECTORY = "./data/cities"

    fun load(plain: () -> WorldGenerator, worldSeed: Long, spawnX: Int, spawnY: Int): List<City> {
        val directory = File(DIRECTORY)
        val saved = CityJson.readAll(directory)
        if (saved.isNotEmpty()) return saved.map { city ->
            val ready = if (CityPlanner.isStub(city)) CityPlanner.make(plain(), city).also { logInfo("Made ${it.name} from seed ${it.seed}: ${it.buildings.size} buildings") } else CityPlanner.upgrade(plain(), city)
            ready.also { if (it != city) CityJson.write(directory, it) }
        }
        val reach = EnvVars.generateSize.takeIf { it > 0 }?.let { it / 2 }
        val low = maxOf(WorldWindow.MAP_ORIGIN, reach?.let { minOf(spawnX, spawnY) - it } ?: WorldWindow.MAP_ORIGIN)
        val high = minOf(WorldWindow.MAP_ORIGIN + WorldWindow.MAP_TILES, reach?.let { maxOf(spawnX, spawnY) + it } ?: Int.MAX_VALUE)
        val planned = CityPlanner.plan(plain(), worldSeed, spawnX, spawnY, low, high)
        planned.forEach { CityJson.write(directory, it) }
        logInfo("Planned ${planned.size} cities: ${planned.joinToString { "${it.name} at ${it.centerX},${it.centerY}" }}")
        return planned
    }
}
