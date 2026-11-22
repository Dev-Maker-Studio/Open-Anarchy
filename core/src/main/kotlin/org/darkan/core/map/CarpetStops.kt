package org.darkan.core.map

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile

class CarpetStop(val arm: Int, val along: Int, val x: Int, val y: Int) {

    val key = "$x,$y"

    val merchant: Tile get() = Tile.of(x + CarpetStops.MERCHANT_OFFSET_X, y, CarpetStops.GROUND)

    val iconX get() = x + CarpetStops.ICON_X
    val iconY get() = y + CarpetStops.ICON_Y

    fun covers(tileX: Int, tileY: Int): Boolean =
        tileX in x + CarpetStops.STAND_MIN_X - CarpetStops.STAND_MARGIN..x + CarpetStops.STAND_MAX_X + CarpetStops.STAND_MARGIN &&
            tileY in y + CarpetStops.STAND_MIN_Y - CarpetStops.STAND_MARGIN..y + CarpetStops.STAND_MAX_Y + CarpetStops.STAND_MARGIN
}

object CarpetStops {
    const val GROUND = 0
    const val ICON_X = 4
    const val ICON_Y = -2
    const val ICON_ROTATION = 2
    const val MERCHANT_OFFSET_X = 3
    const val STAND_MARGIN = 1
    const val STAND_MIN_X = 0
    const val STAND_MAX_X = 5
    const val STAND_MIN_Y = -2
    const val STAND_MAX_Y = 1
    private const val REGION_SHIFT = 6
    private const val VERTICAL_ARMS = 2
    private const val MIN_DESERT_STRETCH = 48
    private const val STAND_INSET = 8
    private const val ROAD_WIDTH = 2
    private const val MAX_ROAD_DISTANCE = 12
    val OUTWARD = arrayOf("North", "South", "East", "West")

    val all: List<CarpetStop> by lazy { if (EnvVars.generateMaps) plan() else emptyList() }

    fun covers(x: Int, y: Int): Boolean = all.any { it.covers(x, y) }

    private fun plan(): List<CarpetStop> {
        val generator = GeneratedWorld.generator
        val stops = ArrayList<CarpetStop>()
        generator.roadArms().forEachIndexed { arm, road ->
            var stretch = 0
            for (along in 0 until road.length) {
                val (x, y) = generator.roadCentreAt(arm, along)
                stretch = if (desert(generator, x, y)) stretch + 1 else 0
                if (stretch == MIN_DESERT_STRETCH) place(generator, arm, along - MIN_DESERT_STRETCH + STAND_INSET)?.let(stops::add)
            }
        }
        logInfo("Carpet riders: " + stops.joinToString { "${OUTWARD[it.arm]} ${it.along} at ${it.key}" })
        return stops
    }

    private fun desert(generator: WorldGenerator, x: Int, y: Int): Boolean =
        generator.inArea(x shr REGION_SHIFT, y shr REGION_SHIFT) && generator.biomeAt(x, y) == WorldGenerator.Biome.DESERT && !GeneratedWorld.inCity(x, y)

    private fun place(generator: WorldGenerator, arm: Int, along: Int): CarpetStop? {
        val (roadX, roadY) = generator.roadCentreAt(arm, along)
        for (distance in 1..MAX_ROAD_DISTANCE) {
            val candidates = if (arm < VERTICAL_ARMS) {
                listOf(roadX + ROAD_WIDTH + distance - STAND_MIN_X to roadY, roadX - 1 - distance - STAND_MAX_X to roadY)
            } else {
                listOf(roadX to roadY + ROAD_WIDTH + distance - STAND_MIN_Y, roadX to roadY - 1 - distance - STAND_MAX_Y)
            }
            for ((x, y) in candidates) {
                val stop = CarpetStop(arm, along, x, y)
                if (clear(generator, stop)) return stop
            }
        }
        return null
    }

    private fun clear(generator: WorldGenerator, stop: CarpetStop): Boolean {
        for (x in stop.x + STAND_MIN_X - STAND_MARGIN..stop.x + STAND_MAX_X + STAND_MARGIN) {
            for (y in stop.y + STAND_MIN_Y - STAND_MARGIN..stop.y + STAND_MAX_Y + STAND_MARGIN) {
                if (!desert(generator, x, y) || generator.onRoad(x, y) || generator.liquidTileAt(x, y) != null) return false
            }
        }
        return true
    }
}
