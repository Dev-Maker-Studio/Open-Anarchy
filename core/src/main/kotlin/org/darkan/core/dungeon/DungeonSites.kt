package org.darkan.core.dungeon

import org.darkan.core.city.City
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import java.util.Random
import kotlin.math.abs

class DungeonSite(val origin: String, val name: String, val theme: DungeonTheme, val seed: Long, val x: Int, val y: Int)

object DungeonSites {
    const val CITY_ORIGIN = "city:"
    const val BIOME_ORIGIN = "biome:"
    private const val REGION = DungeonGrid.REGION
    private const val SALT = 0x5DEECE66DL
    private const val CITY_INSET = 4
    private const val CITY_ATTEMPTS = 4000
    private const val BUILDING_MARGIN = 2
    private const val STREET_REACH = 3
    private const val RESIDENT_REACH = 2
    private const val BIOME_ATTEMPTS = 60_000
    private const val CLEARANCE = 3
    private const val SCENERY_CLEARANCE = 4
    private const val MAX_SLOPE = 2
    private const val CITY_DISTANCE = 60
    private const val LODESTONE_DISTANCE = WorldGenerator.LODESTONE_RADIUS + 8
    private const val SPAWN_DISTANCE = 150

    private val biomeThemes = linkedMapOf(
        Biome.PLAINS to DungeonTheme.FORTRESS,
        Biome.FOREST to DungeonTheme.CAVE,
        Biome.DESERT to DungeonTheme.FORTRESS,
        Biome.WILDERNESS to DungeonTheme.FORTRESS,
        Biome.WINTER to DungeonTheme.CAVE,
        Biome.MOUNTAIN to DungeonTheme.CAVE,
        Biome.VOLCANO to DungeonTheme.CAVE,
    )

    private val biomeNames = mapOf(
        Biome.PLAINS to "Meadow Keep", Biome.FOREST to "Rootbound Hollow", Biome.DESERT to "Sunken Bastion", Biome.WILDERNESS to "Ashen Redoubt",
        Biome.WINTER to "Frostbite Cavern", Biome.MOUNTAIN to "Stonejaw Depths", Biome.VOLCANO to "Cinder Maw",
    )

    fun origins(cities: List<City>): List<String> = cities.map { CITY_ORIGIN + it.id } + biomeThemes.keys.map { BIOME_ORIGIN + it.name.lowercase() }

    fun find(
        origin: String, generator: WorldGenerator, cities: List<City>, lodestones: List<Pair<Int, Int>>, spawnX: Int, spawnY: Int, low: Int, high: Int
    ): DungeonSite? {
        if (origin.startsWith(CITY_ORIGIN)) {
            val city = cities.firstOrNull { it.id == origin.removePrefix(CITY_ORIGIN) } ?: return null
            return citySite(origin, generator, city)
        }
        val biome = biomeThemes.keys.firstOrNull { it.name.equals(origin.removePrefix(BIOME_ORIGIN), ignoreCase = true) } ?: return null
        return biomeSite(origin, biome, generator, cities, lodestones, spawnX, spawnY, low, high)
    }

    fun underBuilding(city: City, x: Int, y: Int): Boolean = city.buildings.any { it.touches(x, y, x, y, BUILDING_MARGIN) }

    private fun citySite(origin: String, generator: WorldGenerator, city: City): DungeonSite? {
        val random = Random(city.seed xor SALT)
        val span = 2 * (city.halfSize - CITY_INSET) + 1
        val pieces = city.pieces().mapTo(HashSet()) { it.x to it.y }
        repeat(CITY_ATTEMPTS) {
            val x = city.centerX - city.halfSize + CITY_INSET + random.nextInt(span)
            val y = city.centerY - city.halfSize + CITY_INSET + random.nextInt(span)
            if (underBuilding(city, x, y)) return@repeat
            if (city.streetTouches(x - 1, y - 1, x + 1, y + 1) || generator.onRoad(x, y)) return@repeat
            if (!city.streetTouches(x - STREET_REACH, y - STREET_REACH, x + STREET_REACH, y + STREET_REACH)) return@repeat
            if (generator.liquidTileAt(x, y) != null || (x to y) in pieces) return@repeat
            if (city.residents.any { abs(it.x - x) <= RESIDENT_REACH && abs(it.y - y) <= RESIDENT_REACH }) return@repeat
            return DungeonSite(origin, "${city.name} Sewers", DungeonTheme.SEWER, random.nextLong(), x, y)
        }
        return null
    }

    private fun biomeSite(
        origin: String, biome: Biome, generator: WorldGenerator, cities: List<City>, lodestones: List<Pair<Int, Int>>,
        spawnX: Int, spawnY: Int, low: Int, high: Int
    ): DungeonSite? {
        val theme = biomeThemes.getValue(biome)
        val random = Random(WorldGenerator.DEFAULT_SEED * SALT + biome.ordinal)
        repeat(BIOME_ATTEMPTS) {
            val x = low + random.nextInt(high - low)
            val y = low + random.nextInt(high - low)
            if (generator.biomeAt(x, y) != biome) return@repeat
            if (maxOf(abs(x - spawnX), abs(y - spawnY)) < SPAWN_DISTANCE) return@repeat
            if (cities.any { it.covers(x, y, CITY_DISTANCE) }) return@repeat
            if (lodestones.any { (lx, ly) -> abs(lx - x) <= LODESTONE_DISTANCE && abs(ly - y) <= LODESTONE_DISTANCE }) return@repeat
            if (!clear(generator, biome, x, y, theme.entranceWidth, theme.entranceDepth)) return@repeat
            return DungeonSite(origin, biomeNames.getValue(biome), theme, random.nextLong(), x, y)
        }
        return null
    }

    private fun clear(generator: WorldGenerator, biome: Biome, x: Int, y: Int, width: Int, depth: Int): Boolean {
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (tx in x - CLEARANCE until x + width + CLEARANCE) for (ty in y - CLEARANCE until y + depth + CLEARANCE) {
            if (generator.biomeAt(tx, ty) != biome || generator.liquidTileAt(tx, ty) != null || generator.onRoad(tx, ty)) return false
            val height = generator.heightAt(tx, ty)
            lowest = minOf(lowest, height)
            highest = maxOf(highest, height)
        }
        if (highest - lowest > MAX_SLOPE) return false
        val minX = x - SCENERY_CLEARANCE
        val minY = y - SCENERY_CLEARANCE
        val maxX = x + width + SCENERY_CLEARANCE
        val maxY = y + depth + SCENERY_CLEARANCE
        for (regionX in minX / REGION..maxX / REGION) for (regionY in minY / REGION..maxY / REGION) {
            val blocked = generator.scenery(regionX, regionY).any {
                val sx = regionX * REGION + it.x
                val sy = regionY * REGION + it.y
                sx in minX..maxX && sy in minY..maxY
            }
            if (blocked) return false
        }
        return true
    }
}
