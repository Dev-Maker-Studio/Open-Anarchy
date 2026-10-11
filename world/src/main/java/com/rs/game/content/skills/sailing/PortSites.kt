package com.rs.game.content.skills.sailing

import org.darkan.core.EnvVars
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import world.gregs.voidps.cache.source.codec.WorldGenerator.Liquid
import java.util.Optional
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2

enum class PortKind { LAKE, OCEAN }

class PortSite(
    val name: String,
    val cellX: Int,
    val cellY: Int,
    val shoreX: Int,
    val shoreY: Int,
    val dirX: Int,
    val dirY: Int,
    val kind: PortKind,
    val threatLevel: Int
) {
    val perpX: Int get() = -dirY
    val perpY: Int get() = dirX

    val alongHeading: Int get() = Math.floorMod(Math.toDegrees(atan2(perpX.toDouble(), perpY.toDouble())).toInt(), FULL_TURN)

    val awayHeading: Int get() = Math.floorMod(Math.toDegrees(atan2(dirX.toDouble(), dirY.toDouble())).toInt(), FULL_TURN)

    fun x(forward: Int, side: Int): Int = shoreX + dirX * forward + perpX * side

    fun y(forward: Int, side: Int): Int = shoreY + dirY * forward + perpY * side

    fun inZone(tileX: Int, tileY: Int, margin: Int = 0): Boolean = abs(tileX - shoreX) <= PortSites.ZONE_RADIUS + margin && abs(tileY - shoreY) <= PortSites.ZONE_RADIUS + margin

    fun berth(type: BoatType, slot: Int): Triple<Int, Int, Int> {
        val side = when {
            slot == 0 -> 0
            slot % 2 == 1 -> (slot + 1) / 2 * PortSites.BERTH_SPACING
            else -> -(slot / 2) * PortSites.BERTH_SPACING
        }
        val forward = PortSites.BERTH_BASE + type.width
        return Triple(x(forward, side), y(forward, side), alongHeading)
    }

    fun sailingLevel(): Int = (threatLevel * PortSites.LEVEL_PER_THREAT).coerceAtMost(PortSites.MAX_PORT_LEVEL)

    fun asPort(): Port = Port(name, shoreX, shoreY, sailingLevel(), threatLevel)

    private companion object {
        const val FULL_TURN = 360
    }
}

object PortSites {
    const val CELL = 256
    const val CELLS = WorldWindow.WINDOW_TILES / CELL
    const val ZONE_RADIUS = 14
    const val BERTH_BASE = 2
    const val BERTH_SPACING = 12
    const val LEVEL_PER_THREAT = 2
    const val MAX_PORT_LEVEL = 99
    private const val ATTEMPTS = 6
    private const val PROBE_STEP = 4
    private const val MAX_REACH = 220
    private const val MIN_WATER_ACROSS = 80
    private const val INLAND_DEPTH = 5
    private const val LATERAL = 3
    private const val WATER_AHEAD = 14
    private const val BERTH_HALF_ALONG = 6
    private const val BERTH_HALF_ACROSS = 3
    private const val EDGE_MARGIN = 160
    private const val SITE_SALT = 0x504F5254L
    private const val MIX_X = 0x9E3779B97F4A7C15uL
    private const val MIX_Y = 0xC2B2AE3D27D4EB4FuL
    private const val REGION_TILES = 64
    private const val TILES_PER_LEVEL = 200
    private val DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private val generator: WorldGenerator get() = GeneratedWorld.generator
    private val sectorX get() = GeneratedWorld.windowX
    private val sectorY get() = GeneratedWorld.windowY
    private val cache = ConcurrentHashMap<Long, Optional<PortSite>>()

    val sector: String get() = sectorName(sectorX, sectorY)

    val enabled: Boolean get() = EnvVars.generateMaps

    fun siteIn(cellX: Int, cellY: Int): PortSite? {
        if (!enabled || cellX !in 0 until CELLS || cellY !in 0 until CELLS) return null
        return cache.computeIfAbsent(cellX.toLong() shl 32 or cellY.toLong()) { Optional.ofNullable(probe(cellX, cellY)) }.orElse(null)
    }

    fun near(x: Int, y: Int, radius: Int): List<PortSite> {
        val minX = Math.floorDiv(x - radius, CELL)
        val maxX = Math.floorDiv(x + radius, CELL)
        val minY = Math.floorDiv(y - radius, CELL)
        val maxY = Math.floorDiv(y + radius, CELL)
        val found = mutableListOf<PortSite>()
        for (cy in minY..maxY) for (cx in minX..maxX) {
            val site = siteIn(cx, cy) ?: continue
            if (abs(site.shoreX - x) <= radius && abs(site.shoreY - y) <= radius) found.add(site)
        }
        return found
    }

    fun nearest(x: Int, y: Int, radius: Int): PortSite? =
        near(x, y, radius).minByOrNull { maxOf(abs(it.shoreX - x), abs(it.shoreY - y)) }

    fun byName(name: String): PortSite? {
        val parts = name.split('-')
        if (parts.size != 2 || parts[0] != sector) return null
        val index = parts[1].toIntOrNull()?.minus(1) ?: return null
        return siteIn(index % CELLS, index / CELLS)
    }

    fun zoneTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean {
        if (!enabled) return false
        return near((minX + maxX) / 2, (minY + maxY) / 2, (maxX - minX) / 2 + ZONE_RADIUS + CELL).any {
            maxX >= it.shoreX - ZONE_RADIUS && minX <= it.shoreX + ZONE_RADIUS && maxY >= it.shoreY - ZONE_RADIUS && minY <= it.shoreY + ZONE_RADIUS
        }
    }

    fun inZone(x: Int, y: Int): Boolean = zoneTouches(x, y, x, y)

    fun isWater(x: Int, y: Int): Boolean = generator.liquidTileAt(x, y) == Liquid.WATER

    fun sailable(x: Int, y: Int): Boolean = inGenerated(x, y) && isWater(x, y)

    fun isLand(x: Int, y: Int): Boolean = land(x, y)

    fun generatedAt(x: Int, y: Int): Boolean = inGenerated(x, y)

    fun biomeAt(x: Int, y: Int): Biome = generator.biomeAt(x, y)

    fun levelAt(x: Int, y: Int): Int = 1 + maxOf(
        abs(WorldWindow.origin(sectorX) + x - WorldGenerator.SPAWN_X),
        abs(WorldWindow.origin(sectorY) + y - WorldGenerator.SPAWN_Y)
    ) / TILES_PER_LEVEL

    private fun inGenerated(x: Int, y: Int): Boolean =
        x in 0 until WorldWindow.WINDOW_TILES && y in 0 until WorldWindow.WINDOW_TILES && generator.inArea(Math.floorDiv(x, REGION_TILES), Math.floorDiv(y, REGION_TILES))

    private fun owned(x: Int, y: Int): Boolean {
        val globalX = WorldWindow.toGlobal(x, sectorX)
        val globalY = WorldWindow.toGlobal(y, sectorY)
        return WorldWindow.owner(globalX) == sectorX && WorldWindow.owner(globalY) == sectorY &&
            WorldWindow.owner(globalX - EDGE_MARGIN) == sectorX && WorldWindow.owner(globalX + EDGE_MARGIN) == sectorX &&
            WorldWindow.owner(globalY - EDGE_MARGIN) == sectorY && WorldWindow.owner(globalY + EDGE_MARGIN) == sectorY &&
            globalX in EDGE_MARGIN until WorldWindow.GRID_TILES - EDGE_MARGIN && globalY in EDGE_MARGIN until WorldWindow.GRID_TILES - EDGE_MARGIN
    }

    private fun land(x: Int, y: Int): Boolean = inGenerated(x, y) && generator.liquidTileAt(x, y) == null

    private fun probe(cellX: Int, cellY: Int): PortSite? {
        val random = Random(WorldGenerator.DEFAULT_SEED xor SITE_SALT xor (cellX * MIX_X.toLong()) xor (cellY * MIX_Y.toLong()) xor (sectorX * 31L + sectorY))
        repeat(ATTEMPTS) {
            val px = cellX * CELL + random.nextInt(CELL)
            val py = cellY * CELL + random.nextInt(CELL)
            val walk = DIRECTIONS[random.nextInt(DIRECTIONS.size)]
            if (inGenerated(px, py) && isWater(px, py)) {
                val site = shoreFrom(cellX, cellY, px, py, walk.first, walk.second)
                if (site != null) return site
            }
        }
        return null
    }

    private fun shoreFrom(cellX: Int, cellY: Int, px: Int, py: Int, walkX: Int, walkY: Int): PortSite? {
        var steps = 0
        var x = px
        var y = py
        while (steps < MAX_REACH && inGenerated(x, y) && isWater(x, y)) {
            x += walkX * PROBE_STEP
            y += walkY * PROBE_STEP
            steps += PROBE_STEP
        }
        if (!land(x, y)) return null
        while (land(x - walkX, y - walkY)) {
            x -= walkX
            y -= walkY
        }
        val dirX = -walkX
        val dirY = -walkY
        val waterX = x + dirX
        val waterY = y + dirY
        if (!isWater(waterX, waterY)) return null
        var back = 0
        var bx = waterX
        var by = waterY
        while (back < MIN_WATER_ACROSS && inGenerated(bx, by) && isWater(bx, by)) {
            bx += dirX
            by += dirY
            back++
        }
        if (back < MIN_WATER_ACROSS) return null
        if (!owned(x, y)) return null
        val kind = if (generator.biomeAt(waterX, waterY) == Biome.OCEAN) PortKind.OCEAN else PortKind.LAKE
        val candidate = PortSite("$sector-${cellY * CELLS + cellX + 1}", cellX, cellY, x, y, dirX, dirY, kind, 1)
        return if (suitable(candidate)) PortSite(candidate.name, cellX, cellY, x, y, dirX, dirY, kind, levelAt(x, y)) else null
    }

    private fun suitable(site: PortSite): Boolean {
        for (back in 0..INLAND_DEPTH) if (!land(site.x(-back, 0), site.y(-back, 0))) return false
        for (side in -LATERAL..LATERAL) if (!land(site.x(0, side), site.y(0, side))) return false
        for (ahead in 1..WATER_AHEAD) if (!isWater(site.x(ahead, 0), site.y(ahead, 0))) return false
        if (!berthClear(site)) return false
        val minX = site.shoreX - ZONE_RADIUS
        val minY = site.shoreY - ZONE_RADIUS
        val maxX = site.shoreX + ZONE_RADIUS
        val maxY = site.shoreY + ZONE_RADIUS
        return !GeneratedWorld.roadTouches(minX, minY, maxX, maxY) && !GeneratedWorld.lodestoneZoneTouches(minX, minY, maxX, maxY)
    }

    private fun berthClear(site: PortSite): Boolean {
        val centre = BERTH_BASE + BoatType.RAFT.width
        for (along in -BERTH_HALF_ALONG..BERTH_HALF_ALONG) for (across in -BERTH_HALF_ACROSS..BERTH_HALF_ACROSS) {
            if (!isWater(site.x(centre + across + 1, along), site.y(centre + across + 1, along))) return false
        }
        return true
    }
}
