package org.darkan.core.city

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
        val planned = CityPlanner.plan(plain(), worldSeed, spawnX, spawnY, WorldWindow.MAP_ORIGIN, WorldWindow.MAP_ORIGIN + WorldWindow.MAP_TILES)
        planned.forEach { CityJson.write(directory, it) }
        logInfo("Planned ${planned.size} cities: ${planned.joinToString { "${it.name} at ${it.centerX},${it.centerY}" }}")
        return planned
    }
}
