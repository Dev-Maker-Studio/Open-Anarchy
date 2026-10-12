package org.darkan.core.map

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.source.codec.WorldGenerator

object GeneratedWorld {

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

    val generator: WorldGenerator by lazy {
        WorldGenerator(WorldGenerator.DEFAULT_SEED, WorldWindow.origin(windowX), WorldWindow.origin(windowY), EnvVars.generateSize)
    }

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

    fun onRoad(x: Int, y: Int): Boolean = EnvVars.generateMaps && generator.onRoad(x, y)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean = EnvVars.generateMaps && generator.roadTouches(minX, minY, maxX, maxY)
}
