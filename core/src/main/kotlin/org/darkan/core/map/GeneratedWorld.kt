package org.darkan.core.map

import org.darkan.core.EnvVars
import org.darkan.core.city.City
import org.darkan.core.city.CityRegistry
import org.darkan.core.dungeon.Dungeon
import org.darkan.core.dungeon.DungeonGrid
import org.darkan.core.dungeon.DungeonRegistry
import world.gregs.voidps.cache.source.codec.WorldGenerator

object GeneratedWorld {

    private const val DUNGEON_MAP_MARGIN = 80
    private const val CAVE_CITY_DISTANCE = 60
    private const val CAVE_ENTRANCE_DISTANCE = 16
    private const val REGION_SHIFT = 6
    private const val REGION_BITS = 8

    private val sector: Pair<Int, Int> = resolveSector()

    val windowX: Int = sector.first
    val windowY: Int = sector.second

    private fun resolveSector(): Pair<Int, Int> {
        val x = EnvVars.worldWindowX
        val y = EnvVars.worldWindowY
        val resolved = when {
            x != null && y != null -> x to y
            EnvVars.worldSector != null -> WorldWindow.sectorOf(EnvVars.worldSector) ?: error("WORLD_SECTOR '${EnvVars.worldSector}' is outside AA..ZZ")
            else -> WorldWindow.CENTER_SECTOR_X to WorldWindow.CENTER_SECTOR_Y
        }
        check(WorldWindow.inGrid(resolved.first, resolved.second)) { "Sector ${resolved.first},${resolved.second} is outside AA..ZZ" }
        check(WorldWindow.isGenerated(resolved.first, resolved.second)) {
            "Sector ${WorldWindow.sectorName(resolved.first, resolved.second)} is not generated, GENERATED_SECTORS is ${EnvVars.generatedSectors}"
        }
        return resolved
    }

    val cities: List<City> by lazy {
        if (!EnvVars.generateMaps) emptyList()
        else CityRegistry.load(::plainGenerator, WorldGenerator.DEFAULT_SEED, spawnX, spawnY)
    }

    private val plain: WorldGenerator by lazy {
        WorldGenerator(WorldGenerator.DEFAULT_SEED, WorldWindow.origin(windowX), WorldWindow.origin(windowY), EnvVars.generateSize)
    }

    fun plainGenerator(): WorldGenerator = plain

    val generator: WorldGenerator by lazy {
        WorldGenerator(WorldGenerator.DEFAULT_SEED, WorldWindow.origin(windowX), WorldWindow.origin(windowY), EnvVars.generateSize, cities)
    }

    val dungeons: List<Dungeon> by lazy {
        if (!EnvVars.generateMaps) emptyList()
        else DungeonRegistry.load(generator, cities, { lodestoneSites }, spawnX, spawnY, WorldWindow.MAP_ORIGIN + DUNGEON_MAP_MARGIN, WorldWindow.MAP_ORIGIN + WorldWindow.MAP_TILES - DUNGEON_MAP_MARGIN)
    }

    fun dungeonAt(x: Int, y: Int): Dungeon? = if (DungeonGrid.covers(x)) dungeons.firstOrNull { it.covers(x, y) } else null

    fun inDungeon(x: Int, y: Int): Boolean =
        EnvVars.generateMaps && (DungeonGrid.covers(x) || (x shr REGION_SHIFT shl REGION_BITS or (y shr REGION_SHIFT)) in instanceRegions)

    fun cityAt(x: Int, y: Int): City? = cities.firstOrNull { it.covers(x, y) }

    fun nearestCity(x: Int, y: Int): City? = cities.minByOrNull { maxOf(Math.abs(it.centerX - x), Math.abs(it.centerY - y)) }

    val homeCity: City? by lazy { nearestCity(spawnX, spawnY) }

    fun inCity(x: Int, y: Int): Boolean = EnvVars.generateMaps && cityAt(x, y) != null

    fun cityTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = cities.any { it.touches(minX, minY, maxX, maxY) }

    /** Land within [CITY_CLAIM_MARGIN] tiles of a city's edge can't be claimed. */
    const val CITY_CLAIM_MARGIN = 100

    fun cityClaimZoneTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        EnvVars.generateMaps && cities.any { it.touches(minX, minY, maxX, maxY, CITY_CLAIM_MARGIN) }

    fun inCityClaimZone(x: Int, y: Int): Boolean = cityClaimZoneTouches(x, y, x, y)

    val sectorName: String = WorldWindow.sectorName(windowX, windowY)

    val spawnX: Int = WorldWindow.toLocal(WorldGenerator.ORIGIN_X, windowX).coerceIn(0, WorldWindow.WINDOW_TILES - 1)
    val spawnY: Int = WorldWindow.toLocal(WorldGenerator.ORIGIN_Y, windowY).coerceIn(0, WorldWindow.WINDOW_TILES - 1)

    val lodestoneSites: List<Pair<Int, Int>> by lazy { if (EnvVars.generateMaps) generator.lodestoneSites() else emptyList() }

    fun lodestoneZoneTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        lodestoneSites.any { (x, y) ->
            maxX >= x - WorldGenerator.LODESTONE_RADIUS && minX <= x + WorldGenerator.LODESTONE_RADIUS &&
                maxY >= y - WorldGenerator.LODESTONE_RADIUS && minY <= y + WorldGenerator.LODESTONE_RADIUS
        }

    fun inLodestoneZone(x: Int, y: Int): Boolean = lodestoneZoneTouches(x, y, x, y)

    val caveSites: List<Pair<Int, Int>> by lazy {
        if (!EnvVars.generateMaps) emptyList()
        else generator.caveSites().filter { (x, y) ->
            cities.none { it.covers(x, y, CAVE_CITY_DISTANCE) } && dungeons.none { it.entranceTouches(x, y, CAVE_ENTRANCE_DISTANCE) }
        }
    }

    fun caveAt(x: Int, y: Int, margin: Int = 0): Pair<Int, Int>? = caveSites.firstOrNull { (caveX, caveY) ->
        x >= caveX - margin && x < caveX + WorldGenerator.CAVE_SIZE + margin && y >= caveY - margin && y < caveY + WorldGenerator.CAVE_SIZE + margin
    }

    val instanceRegions: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun onRoad(x: Int, y: Int): Boolean = EnvVars.generateMaps && generator.onRoad(x, y)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = EnvVars.generateMaps && generator.roadTouches(minX, minY, maxX, maxY)
}
