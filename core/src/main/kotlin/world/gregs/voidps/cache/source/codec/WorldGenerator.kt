package world.gregs.voidps.cache.source.codec

import org.darkan.core.city.City
import org.darkan.core.city.ChurchTemplate
import org.darkan.core.city.FloorStyle
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Overlay
import org.darkan.core.gameval.gen.Underlay
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class WorldGenerator(
    private val seed: Long = DEFAULT_SEED,
    private val originX: Int = 0,
    private val originY: Int = 0,
    private val areaTiles: Int = 0,
    private val cities: List<City> = emptyList()
) : MapGenerator {

    fun inArea(regionX: Int, regionY: Int): Boolean {
        val span = (areaTiles + SIZE - 1) / SIZE
        val sectorRegions = (WorldWindow.WINDOW_TILES + SIZE - 1) / SIZE
        if (regionX !in 0 until sectorRegions || regionY !in 0 until sectorRegions) return false
        if (areaTiles <= 0) return true
        val lowX = Math.floorDiv(ORIGIN_X - areaTiles / 2, SIZE)
        val lowY = Math.floorDiv(ORIGIN_Y - areaTiles / 2, SIZE)
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        return globalX in lowX until lowX + span && globalY in lowY until lowY + span
    }

    fun voidTerrain(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
        val void = rows { _ -> row { _ -> VOID_TILE } }
        val text = StringBuilder(TEXT_CAPACITY)
        text.append("{\n  \"region\": \"").append(square.directory).append("\",\n  \"levels\": 4,\n  \"tiles\": [\n")
        text.append(void).append(",\n").append(void).append(",\n").append(void).append(",\n").append(void)
        text.append("\n  ],\n  \"effects\": ").append(EFFECTS).append("\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    fun emptyLocations(regionX: Int, regionY: Int): ByteArray =
        MapJson.writeLocs(MapDefinition(regionY + (regionX shl 8), levels = 0), MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY))

    class Scenery(val id: Int, val size: Int, val weight: Int, val shape: Int = SCENERY_SHAPE)

    enum class Biome(val underlay: Int, val odds: Long, val scenery: List<Scenery>) {
        PLAINS(
            PLAINS_UNDERLAY, PLAINS_ODDS,
            listOf(
                Scenery(Loc.tree, 2, 2), Scenery(Loc.boulder1, 2, 1), Scenery(Loc.boulder3, 1, 1), Scenery(Loc.bush2, 1, 2),
                Scenery(Loc.grass_1, 1, 1, GROUND_DECOR_SHAPE), Scenery(Loc.grass_2, 1, 1, GROUND_DECOR_SHAPE),
                Scenery(Loc.grass_3, 1, 1, GROUND_DECOR_SHAPE), Scenery(Loc.grass_4, 1, 1, GROUND_DECOR_SHAPE)
            )
        ),
        DESERT(
            DESERT_UNDERLAY, DESERT_ODDS,
            listOf(
                Scenery(Loc.boulder1, 2, 3), Scenery(Loc.boulder2, 2, 3), Scenery(Loc.boulder3, 1, 3), Scenery(Loc.boulder4, 1, 3),
                Scenery(Loc.cactus, 1, 4), Scenery(Loc.cactus2, 2, 1), Scenery(Loc.cactus3, 2, 1), Scenery(Loc.cactus4, 2, 1), Scenery(Loc.cactus5, 2, 1),
                Scenery(Loc.palm, 2, 3), Scenery(Loc.palm2, 2, 3)
            )
        ),
        FOREST(FOREST_UNDERLAY, FOREST_ODDS, listOf(Scenery(Loc.tree, 2, 3), Scenery(Loc.oaktree, 3, 2), Scenery(Loc.willowtree, 2, 2))),
        WILDERNESS(
            WILDERNESS_UNDERLAY, WILDERNESS_ODDS,
            listOf(Scenery(Loc.deadtree1, 2, 2), Scenery(Loc.deadtree2, 1, 3), Scenery(Loc.deadtree4, 2, 2))
        ),
        WINTER(
            WINTER_UNDERLAY, WINTER_ODDS,
            listOf(
                Scenery(Loc.snowtree1, 3, 2), Scenery(Loc.snowtree2, 3, 2), Scenery(Loc.snowtree3, 3, 2), Scenery(Loc.loc_21273, 2, 2),
                Scenery(Loc.deadtree1, 2, 2), Scenery(Loc.deadtree2, 1, 2), Scenery(Loc.deadtree2_snowy, 1, 2),
                Scenery(Loc.boulder1, 2, 1), Scenery(Loc.boulder3, 1, 2)
            )
        ),
        OCEAN(OCEAN_UNDERLAY, Long.MAX_VALUE, emptyList()),
        MOUNTAIN(MOUNTAIN_UNDERLAY, MOUNTAIN_ODDS, MOUNTAIN_SLOPE_SCENERY),
        VOLCANO(
            VOLCANO_UNDERLAY, VOLCANO_ODDS,
            listOf(
                Scenery(Loc.boulder1, 2, 3), Scenery(Loc.boulder2, 2, 3), Scenery(Loc.boulder3, 1, 3), Scenery(Loc.boulder4, 1, 3),
                Scenery(Loc.deadtree_burnt, 1, 2), Scenery(Loc.deadtree6, 1, 1),
                Scenery(Loc.lavafire, 1, 3), Scenery(Loc.lavabubbles, 1, 2),
                Scenery(Loc.mah5_blackstone_smashed, 1, 4), Scenery(Loc.rocko_prison_sharp_rock01, 1, 3),
                Scenery(Loc.stagamite_small_grey, 1, 3), Scenery(Loc.charred_bones, 1, 2), Scenery(Loc.fire_remains, 1, 1),
                Scenery(Loc.rand_abnd_furn_religious_decor_short_lit, 1, 1), Scenery(Loc.ken_mine_vent_tinted, 1, 2),
                Scenery(Loc.carcass_burnt, 2, 1)
            )
        ),
        RUNE_ESSENCE(
            RUNE_ESSENCE_UNDERLAY, RUNE_ESSENCE_ODDS,
            listOf(
                Scenery(Loc.di2_snow_rock1, 2, 2), Scenery(Loc.di2_snow_rock2, 2, 2), Scenery(Loc.di2_snow_rock1_small, 1, 3),
                Scenery(Loc.deadtree2_snowy, 1, 2), Scenery(Loc.di2_snow_rune_rocks_big1, 2, 1), Scenery(Loc.di2_snow_rune_rocks_big2, 2, 1),
                Scenery(Loc.snow1, 1, 3, GROUND_DECOR_SHAPE), Scenery(Loc.snow2, 1, 3, GROUND_DECOR_SHAPE),
                Scenery(Loc.dugupsmallrocks_lightgrey1, 1, 2, GROUND_DECOR_SHAPE)
            )
        )
    }

    enum class Liquid(val overlay: Int, val underlay: Int, val smelt: Boolean) {
        WATER(WATER_OVERLAY, WATER_UNDERLAY, false),
        LAVA(LAVA_OVERLAY, WILDERNESS_UNDERLAY, true)
    }

    enum class FishingKind(val npc: Int) {
        NET(Npc._0_47_57_saltfish),
        CAGE(Npc._0_40_53_rarefish),
        HARPOON(Npc._0_45_49_rarefish),
        LURE(Npc._0_37_53_freshfish),
        SHARK(Npc._0_40_53_memberfish)
    }

    class FishingSite(val x: Int, val y: Int, val kind: FishingKind)

    private inner class Site(val cellX: Int, val cellY: Int, val x: Double, val y: Double, val stretchX: Double, val stretchY: Double, val biome: Biome) {
        val centreHeight: Double by lazy { land(x.toInt(), y.toInt()) }
        val chambers: List<Pair<Int, Int>> by lazy {
            listOf(-1, 1).flatMap { sx -> listOf(-1, 1).map { sy -> x.toInt() + sx * MINE_DX to y.toInt() + sy * MINE_DY } }
        }
    }

    private class Fixture(val id: Int, val x: Int, val y: Int, val width: Int, val depth: Int, val rotation: Int, val reach: Int = 0)

    private class Island(val x: Int, val y: Int, val radius: Double, val fixtures: List<Fixture>)

    private class Peak(val x: Double, val y: Double, val radius: Double, val scale: Double)

    private class Trail(val xs: DoubleArray, val ys: DoubleArray)

    private class Clearing(val x: Double, val y: Double, val radius: Double, val loc: Int, val count: Int, val size: Int)

    private class Range(val peaks: List<Peak>, val trails: List<Trail>, val clearings: List<Clearing>) {

        fun gap(x: Double, y: Double): Double {
            var best = Double.MAX_VALUE
            for (trail in trails) {
                for (index in 0 until trail.xs.size - 1) {
                    best = minOf(best, segmentDistance(x, y, trail.xs[index], trail.ys[index], trail.xs[index + 1], trail.ys[index + 1]))
                }
            }
            return best
        }

        fun walkable(x: Int, y: Int): Boolean {
            val centreX = x + HALF_TILE
            val centreY = y + HALF_TILE
            if (gap(centreX, centreY) <= PATH_HALF_WIDTH) return true
            if (clearings.any { hypot(centreX - it.x, centreY - it.y) <= it.radius }) return true
            return peaks.any { hypot(centreX - it.x, centreY - it.y) <= SUMMIT_RADIUS }
        }

        fun mass(x: Int, y: Int): Double {
            var open = 1.0
            for (peak in peaks) {
                open *= 1 - ((1 - hypot(x - peak.x, y - peak.y) / peak.radius) * peak.scale).coerceIn(0.0, 1.0)
            }
            return 1 - open
        }

        private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            val along = if (length == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / length).coerceIn(0.0, 1.0)
            return hypot(px - (ax + dx * along), py - (ay + dy * along))
        }
    }

    private class Profile(val rocks: List<Pair<Int, Int>>, val yews: Int)

    private class Layout(val rocks: List<Int>, val side: Int)

    private class OreCluster(val rocks: List<MapObject>, val globalX: List<Int>, val globalY: List<Int>, val centreX: Int, val centreY: Int, val side: Int)

    private class Grid(
        val biomes: Array<Array<Biome>>,
        val liquids: Array<Array<Liquid?>>,
        val heights: Array<IntArray>,
        val underlays: Array<IntArray>,
        val roads: Array<BooleanArray>,
        val depths: Array<IntArray>?,
        val blocked: Array<BooleanArray>
    )

    private val craterRim = VOLCANO_LIFT * smooth(1 - (CRATER_RADIUS + CRATER_BANK) / VOLCANO_RADIUS)

    private val dryRows: String by lazy { rows { _ -> row { _ -> VOID_TILE } } }

    private val road = RoadNetwork(originX, originY, WorldWindow.WINDOW_TILES, ORIGIN_X, ORIGIN_Y, ::roadProbe) { a, b -> noise(a, b, ROAD_SALT) }

    private val sites = ConcurrentHashMap<Long, Site>()

    private val grids = ConcurrentHashMap<Long, Grid>()

    private val fixtures = ConcurrentHashMap<Long, List<Fixture>>()

    private val stations = ConcurrentHashMap<Long, List<Fixture>>()

    private val islands = ConcurrentHashMap<Long, List<Island>>()

    private val clusters = ConcurrentHashMap<Long, List<OreCluster>>()

    private val ranges = ConcurrentHashMap<Long, Range>()

    private val islandPlants = ConcurrentHashMap<Long, List<Fixture>>()

    private val islandSharks = ConcurrentHashMap<Long, List<Pair<Int, Int>>>()

    private val fishing = ConcurrentHashMap<Long, List<FishingSite>>()

    override fun terrain(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
        val grid = grid(originX / SIZE + regionX, originY / SIZE + regionY)
        val void = rows { _ -> row { _ -> VOID_TILE } }
        val floors = buildingFloors(regionX, regionY)
        val surface = rows { localX -> row { localY -> floors[floorKey(localX, localY, GROUND_LEVEL)]?.let { groundFloorTile(grid, localX, localY, it) } ?: tile(grid, localX, localY) } }
        val upper = (1 until UPPER_LEVELS + 1).map { level ->
            if (floors.keys.none { it shr FLOOR_LEVEL_SHIFT == level }) void
            else rows { localX -> row { localY -> floors[floorKey(localX, localY, level)] ?: VOID_TILE } }
        }
        val text = StringBuilder(TEXT_CAPACITY)
        text.append("{\n  \"region\": \"").append(square.directory).append("\",\n  \"levels\": 4,\n  \"tiles\": [\n")
        text.append(surface).append(",\n").append(upper.joinToString(",\n"))
        text.append("\n  ],\n  \"effects\": ").append(EFFECTS).append("\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    private fun floorKey(localX: Int, localY: Int, level: Int) = level shl FLOOR_LEVEL_SHIFT or (localX shl FLOOR_AXIS_SHIFT) or localY

    private fun buildingFloors(regionX: Int, regionY: Int): Map<Int, String> {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        val result = HashMap<Int, String>()
        for (city in cities) {
            if (!city.touches(baseX, baseY, baseX + SIZE - 1, baseY + SIZE - 1)) continue
            for (building in city.buildings) {
                val church = building.kind == ChurchTemplate.KIND
                val floor = "o${FloorStyle.overlayOf(building.floor)}/0/0,s$ROOFED_TILE"
                for (x in maxOf(building.x, baseX)..minOf(building.maxX, baseX + SIZE - 1)) for (y in maxOf(building.y, baseY)..minOf(building.maxY, baseY + SIZE - 1)) {
                    for (level in 0 until if (church) ChurchTemplate.LEVELS else building.levels) {
                        val tile = if (church) ChurchTemplate.tile(level, x - building.x, y - building.y) else if (level == GROUND_LEVEL) floor else "$floor,h$UPPER_FLOOR_HEIGHT"
                        if (tile != null) result[floorKey(x - baseX, y - baseY, level)] = tile
                    }
                }
            }
        }
        return result
    }

    private fun groundFloorTile(grid: Grid, localX: Int, localY: Int, floor: String) =
        "$floor,u${grid.underlays[localX][localY]},h${grid.heights[localX][localY]}"

    fun underwaterTerrain(regionX: Int, regionY: Int, surface: ByteArray? = null): ByteArray? {
        val grid = grid(originX / SIZE + regionX, originY / SIZE + regionY, true)
        val water = surface?.let { waterTiles(it, regionX, regionY) }
        val depths = grid.depths!!
        var wet = false
        val wetRows = rows { localX ->
            row { localY ->
                val deep = if (water == null) grid.liquids[localX][localY] == Liquid.WATER else water[localX][localY]
                if (deep) wet = true
                if (deep) bedTile(depths[localX][localY]) else shoreTile(grid.underlays[localX][localY])
            }
        }
        return if (wet) underwaterText(regionX, regionY, wetRows) else null
    }

    fun dryUnderwaterTerrain(regionX: Int, regionY: Int): ByteArray = underwaterText(regionX, regionY, dryRows)

    private fun underwaterText(regionX: Int, regionY: Int, rows: String): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.UNDERWATER_TERRAIN, regionX, regionY)
        val text = StringBuilder(TEXT_CAPACITY)
        text.append("{\n  \"region\": \"").append(square.directory).append("\",\n  \"levels\": 1,\n  \"tiles\": [\n")
        text.append(rows).append("\n  ]\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    fun underwaterLocations(regionX: Int, regionY: Int): ByteArray =
        MapJson.writeLocs(MapDefinition(regionY + (regionX shl 8), levels = 0), MapSquare.Square(MapSquare.Shape.UNDERWATER_LOCATIONS, regionX, regionY))

    private fun waterTiles(surface: ByteArray, regionX: Int, regionY: Int): Array<BooleanArray> {
        val definition = MapJson.readTerrain(surface, MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY))
        return Array(SIZE) { x -> BooleanArray(SIZE) { y -> definition.getTile(x, y, GROUND_LEVEL).overlayId == WATER_OVERLAY } }
    }

    private fun bedTile(depth: Int): String {
        val underlay = when {
            depth < SAND_DEPTH -> SAND_BED_UNDERLAY
            depth < MUD_DEPTH -> MUD_BED_UNDERLAY
            else -> DEEP_BED_UNDERLAY
        }
        return if (depth == 0) "u$underlay" else "u$underlay,h$depth"
    }

    private fun shoreTile(underlay: Int): String = "o$HIDDEN_OVERLAY/0/0,u$underlay"

    override fun locations(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY)
        val definition = MapDefinition(regionY + (regionX shl 8), levels = 0)
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        val grid = grid(globalX, globalY)
        placeSmelting(definition, grid)
        return MapJson.writeLocs(definition, square)
    }

    fun scenery(regionX: Int, regionY: Int): List<MapObject> {
        val definition = MapDefinition(regionY + (regionX shl 8), levels = 0)
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        placeScenery(definition, grid(globalX, globalY), globalX, globalY)
        placeFixtures(definition, globalX, globalY)
        placeOreClusters(definition, globalX, globalY)
        if (cities.isNotEmpty()) definition.objects.removeAll { scenery -> cities.any { it.covers(regionX * SIZE + scenery.x, regionY * SIZE + scenery.y) } }
        return definition.objects
    }

    fun liquidTileAt(x: Int, y: Int): Liquid? {
        val globalX = x + originX
        val globalY = y + originY
        val regionX = Math.floorDiv(globalX, SIZE)
        val regionY = Math.floorDiv(globalY, SIZE)
        if (grids.size > GRID_CACHE_LIMIT) {
            grids.clear()
        }
        val grid = grids.computeIfAbsent(cellKey(regionX, regionY)) { grid(regionX, regionY) }
        return grid.liquids[Math.floorMod(globalX, SIZE)][Math.floorMod(globalY, SIZE)]
    }

    fun heightAt(x: Int, y: Int): Int {
        val globalX = x + originX
        val globalY = y + originY
        val regionX = Math.floorDiv(globalX, SIZE)
        val regionY = Math.floorDiv(globalY, SIZE)
        val grid = grids.computeIfAbsent(cellKey(regionX, regionY)) { grid(regionX, regionY) }
        return grid.heights[Math.floorMod(globalX, SIZE)][Math.floorMod(globalY, SIZE)]
    }

    fun roadCentreAt(arm: Int, along: Int): Pair<Int, Int> {
        val road = road.arms[arm]
        val index = along.coerceIn(0, road.length - 1)
        val (x, y) = road.tile(index, road.offsets[index].toInt())
        return x - originX to y - originY
    }

    fun onRoad(x: Int, y: Int): Boolean = roadAt(x + originX, y + originY)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        roadTouchesGlobal(minX + originX, minY + originY, maxX + originX, maxY + originY)

    fun roadArms(): List<RoadNetwork.Arm> = road.arms

    fun roadHazard(x: Int, y: Int): Boolean = roadProbe(x + originX, y + originY) and RoadNetwork.HAZARD != 0

    private fun roadAt(x: Int, y: Int): Boolean = roadTouchesGlobal(x, y, x, y)

    private fun roadTouchesGlobal(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        road.touches(minX, minY, maxX, maxY) || cities.any { it.streetTouches(minX - originX, minY - originY, maxX - originX, maxY - originY) }

    private fun roadProbe(x: Int, y: Int): Int {
        val site = nearestSite(x, y)
        val biome = site.biome
        val lake = lake(x, y)
        val pond = pond(x, y)
        val basin = if (biome in DRY_BIOMES) 0.0 else maxOf(
            (lake - BANK_START) / (lakeLevel(biome) - BANK_START),
            if (biome == Biome.DESERT) (pond - POND_BANK_START) / (POND_LEVEL - POND_BANK_START) else 0.0
        )
        val wet = biome == Biome.OCEAN || liquid(biome, lake, pond, false, craterField(x, y, site)) != null || basin >= ROAD_SHORE_BASIN ||
            biome == Biome.VOLCANO && craterField(x, y, site) + CRATER_BANK > 0 || fieldOf(distances(x, y), Biome.OCEAN) > -SHORE_BLEND
        return biome.ordinal or (if (wet) RoadNetwork.HAZARD else 0) or (if (ranged(biome) || biome == Biome.RUNE_ESSENCE) RoadNetwork.ROUGH else 0)
    }

    private fun inSector(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        minX >= originX && minY >= originY && maxX < originX + WorldWindow.WINDOW_TILES && maxY < originY + WorldWindow.WINDOW_TILES

    fun biomeAt(x: Int, y: Int): Biome = biomeAtGlobal(x + originX, y + originY)

    private fun tile(grid: Grid, localX: Int, localY: Int): String {
        if (grid.roads[localX][localY]) {
            return "o$ROAD_OVERLAY/0/0,u${grid.underlays[localX][localY]},h${grid.heights[localX][localY]}"
        }
        val liquid = grid.liquids[localX][localY]
        if (liquid != null) {
            return "o${liquid.overlay}/0/0,s1,u${liquid.underlay},h${if (liquid == Liquid.LAVA) grid.heights[localX][localY] else WATER_TILE_HEIGHT}"
        }
        val blocked = if (grid.blocked[localX][localY]) ",s1" else ""
        return "u${grid.underlays[localX][localY]},h${grid.heights[localX][localY]}$blocked"
    }

    private fun grid(regionX: Int, regionY: Int, withDepth: Boolean = false): Grid {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        val nearest = Array(SIZE + 1) { dx -> Array(SIZE + 1) { dy -> nearestSite(baseX + dx, baseY + dy) } }
        val biomes = Array(SIZE + 1) { dx -> Array(SIZE + 1) { dy -> nearest[dx][dy].biome } }
        val lake = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> lake(baseX + dx, baseY + dy) } }
        val pond = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> pond(baseX + dx, baseY + dy) } }
        val island = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> if (biomes[dx][dy] == Biome.OCEAN) islandLift(baseX + dx, baseY + dy, nearest[dx][dy]) else 0.0 } }
        val crater = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> craterField(baseX + dx, baseY + dy, nearest[dx][dy]) } }
        val masses = Array(SIZE) { DoubleArray(SIZE) }
        val gaps = Array(SIZE) { DoubleArray(SIZE) { Double.MAX_VALUE } }
        val walkable = Array(SIZE) { BooleanArray(SIZE) }
        for (dx in 0 until SIZE) {
            for (dy in 0 until SIZE) {
                val site = nearest[dx][dy]
                if (!ranged(site.biome)) continue
                val range = rangeOf(site)
                val fade = ramp(fieldOf(distances(baseX + dx, baseY + dy), site.biome), RANGE_BORDER_FADE)
                masses[dx][dy] = range.mass(baseX + dx, baseY + dy) * fade
                gaps[dx][dy] = range.gap(baseX + dx + HALF_TILE, baseY + dy + HALF_TILE)
                walkable[dx][dy] = range.walkable(baseX + dx, baseY + dy)
            }
        }
        val points = Array(SIZE + 1) { dx ->
            Array(SIZE + 1) { dy ->
                liquid(biomes[dx][dy], lake[dx][dy], pond[dx][dy], island[dx][dy] > 0, crater[dx][dy])
            }
        }
        val liquids = Array(SIZE) { dx -> Array(SIZE) { dy -> tileLiquid(points, dx, dy) } }
        val heights = Array(SIZE) { dx ->
            IntArray(SIZE) { dy -> height(baseX + dx, baseY + dy, nearest[dx][dy], lake[dx][dy], pond[dx][dy], island[dx][dy], crater[dx][dy], masses[dx][dy], gaps[dx][dy]) }
        }
        flatten(heights, baseX, baseY)
        val underlays = Array(SIZE) { dx ->
            IntArray(SIZE) { dy -> underlay(baseX + dx, baseY + dy, nearest[dx][dy], heights[dx][dy], crater[dx][dy], gaps[dx][dy]) }
        }
        val roads = Array(SIZE) { dx -> BooleanArray(SIZE) { dy -> roadAt(baseX + dx, baseY + dy) } }
        val depths = if (!withDepth) null else Array(SIZE) { dx ->
            IntArray(SIZE) { dy ->
                if (liquids[dx][dy] == Liquid.WATER) depth(baseX + dx, baseY + dy, nearest[dx][dy], lake[dx][dy], pond[dx][dy]) else 0
            }
        }
        val blocked = Array(SIZE) { dx ->
            BooleanArray(SIZE) { dy -> masses[dx][dy] >= BODY_THRESHOLD && !walkable[dx][dy] && liquids[dx][dy] == null && !roads[dx][dy] }
        }
        return Grid(Array(SIZE) { dx -> Array(SIZE) { dy -> biomes[dx][dy] } }, liquids, heights, underlays, roads, depths, blocked)
    }

    private fun flatten(heights: Array<IntArray>, baseX: Int, baseY: Int) {
        for (city in cities) {
            val centreX = city.centerX + originX
            val centreY = city.centerY + originY
            val reach = city.halfSize + CITY_BLEND
            if (baseX + SIZE <= centreX - reach || baseX > centreX + reach || baseY + SIZE <= centreY - reach || baseY > centreY + reach) continue
            for (dx in 0 until SIZE) for (dy in 0 until SIZE) {
                val outside = maxOf(abs(baseX + dx - centreX), abs(baseY + dy - centreY)) - city.halfSize
                if (outside >= CITY_BLEND) continue
                val weight = if (outside <= 0) 1.0 else smooth(1.0 - outside.toDouble() / CITY_BLEND)
                heights[dx][dy] = lerp(heights[dx][dy].toDouble(), city.baseHeight.toDouble(), weight).toInt()
            }
        }
    }

    private fun height(x: Int, y: Int, site: Site, lake: Double, pond: Double, island: Double, crater: Double, mass: Double, pathGap: Double): Int {
        val biome = site.biome
        val distances = distances(x, y)
        var terrain = land(x, y)
        if (mass > 0) {
            val lift = smooth(mass.coerceAtMost(1.0))
            if (biome == Biome.VOLCANO) {
                terrain = lerp(terrain, site.centreHeight, lift) + VOLCANO_LIFT * lift
            } else {
                terrain += RANGE_LIFT * lift + mass * (noise(x / MOUNTAIN_PEAK_WAVELENGTH, y / MOUNTAIN_PEAK_WAVELENGTH, MOUNTAIN_SALT) * MOUNTAIN_PEAKS + cliffs(x, y, pathGap))
            }
        }
        if (biome == Biome.VOLCANO) {
            terrain = lerp(terrain, site.centreHeight + craterRim - CRATER_DEPTH, smooth(((crater + CRATER_BANK) / CRATER_BANK).coerceIn(0.0, 1.0)))
        }
        if (biome == Biome.RUNE_ESSENCE) {
            terrain = mineHeight(terrain, site, x, y)
        }
        val basin = if (biome in DRY_BIOMES) 0.0 else maxOf(
            ((lake - BANK_START) / (lakeLevel(biome) - BANK_START)).coerceIn(0.0, 1.0),
            if (biome == Biome.DESERT) ((pond - POND_BANK_START) / (POND_LEVEL - POND_BANK_START)).coerceIn(0.0, 1.0) else 0.0
        )
        terrain = lerp(terrain, WATER_HEIGHT.toDouble(), basin)
        terrain = lerp(terrain, WATER_HEIGHT.toDouble(), ramp(fieldOf(distances, Biome.OCEAN) + SHORE_BLEND, SHORE_BLEND))
        if (island > 0) {
            val peak = ISLAND_PEAK + (noise(x / ISLAND_BUMP_WAVELENGTH, y / ISLAND_BUMP_WAVELENGTH, HEIGHT_SALT) - 0.5) * 2 * ISLAND_BUMP
            terrain = lerp(WATER_HEIGHT.toDouble(), peak, smooth((island / ISLAND_SLOPE).coerceIn(0.0, 1.0)))
        }
        return terrain.toInt().coerceIn(WATER_HEIGHT, MAX_HEIGHT)
    }

    private fun cliffs(x: Int, y: Int, pathGap: Double): Double {
        val ridge = noise(x / CLIFF_WAVELENGTH, y / CLIFF_WAVELENGTH, CLIFF_SALT)
        var steps = 0.0
        for (level in CLIFF_LEVELS) {
            steps += smooth(((ridge - level) / CLIFF_EDGE).coerceIn(0.0, 1.0))
        }
        return steps * CLIFF_STEP * smooth(((pathGap - PATH_HALF_WIDTH) / CLIFF_PATH_CLEARANCE).coerceIn(0.0, 1.0))
    }

    private fun craterField(x: Int, y: Int, site: Site): Double =
        if (site.biome == Biome.VOLCANO) CRATER_RADIUS - hypot(x - site.x, y - site.y) else Double.NEGATIVE_INFINITY

    private fun mineField(site: Site, x: Int, y: Int): Double =
        site.chambers.minOf { hypot((x + HALF_TILE - it.first) / MINE_A, (y + HALF_TILE - it.second) / MINE_B) }

    private fun mineHeight(terrain: Double, site: Site, x: Int, y: Int): Double {
        val field = mineField(site, x, y)
        val flat = 1 - smooth(((field - 1) / MINE_FLAT_SPAN).coerceIn(0.0, 1.0))
        val pit = 1 - smooth(((field - MINE_FLOOR) / (1 - MINE_FLOOR)).coerceIn(0.0, 1.0))
        return lerp(terrain, site.centreHeight, flat) - MINE_DEPTH * pit
    }

    private fun ranged(biome: Biome) = biome == Biome.MOUNTAIN || biome == Biome.VOLCANO

    private fun underlay(x: Int, y: Int, site: Site, height: Int, crater: Double, pathGap: Double): Int = when (site.biome) {
        Biome.MOUNTAIN -> when {
            pathGap <= PATH_HALF_WIDTH -> TRAIL_UNDERLAY
            height >= SNOW_LINE -> WINTER_UNDERLAY
            height < FOOT_LINE -> FOREST_UNDERLAY
            else -> MOUNTAIN_UNDERLAY
        }
        Biome.VOLCANO -> when {
            pathGap <= PATH_HALF_WIDTH -> TRAIL_UNDERLAY
            crater > -EMBER_RING -> EMBER_UNDERLAY
            noise(x / ASH_WAVELENGTH, y / ASH_WAVELENGTH, ASH_SALT) < OBSIDIAN_SHARE -> OBSIDIAN_UNDERLAY
            else -> ASH_UNDERLAY
        }
        Biome.OCEAN -> if (height <= BEACH_HEIGHT) BEACH_UNDERLAY else site.biome.underlay
        Biome.RUNE_ESSENCE -> mineField(site, x, y).let { field ->
            when {
                field < MINE_FLOOR -> MINE_FLOOR_UNDERLAY
                field < MINE_RING -> MINE_RING_UNDERLAY
                field < 1 -> WINTER_UNDERLAY
                noise(x / RUNE_PATCH_WAVELENGTH, y / RUNE_PATCH_WAVELENGTH, RUNE_PATCH_SALT) < RUNE_PATCH_SHARE -> MINE_RING_UNDERLAY
                else -> site.biome.underlay
            }
        }
        else -> site.biome.underlay
    }

    private fun rangeOf(site: Site): Range = ranges.computeIfAbsent(cellKey(site.cellX, site.cellY)) { buildRange(site) }

    private fun pathNear(x: Int, y: Int, size: Int): Boolean {
        val site = nearestSite(x, y)
        if (!ranged(site.biome)) return false
        val range = rangeOf(site)
        for (dx in -1..size) {
            for (dy in -1..size) {
                if (range.walkable(x + dx, y + dy)) return true
            }
        }
        return false
    }

    private fun depth(x: Int, y: Int, site: Site, lake: Double, pond: Double): Int {
        val bed = 1 + (noise(x / BED_WAVELENGTH, y / BED_WAVELENGTH, BED_SALT) - 0.5) * BED_VARIATION
        val body = when {
            site.biome == Biome.OCEAN -> {
                val slope = smooth((fieldOf(distances(x, y), Biome.OCEAN) / OCEAN_SLOPE).coerceIn(0.0, 1.0))
                OCEAN_DEPTH * slope * smooth((islandGap(x, y, site) / ISLAND_SHELF).coerceIn(0.0, 1.0))
            }
            site.biome == Biome.DESERT && pond >= POND_LEVEL ->
                POND_DEPTH * smooth(((pond - POND_LEVEL) / POND_SPAN).coerceIn(0.0, 1.0))
            else -> LAKE_DEPTH * smooth(((lake - lakeLevel(site.biome)) / LAKE_SPAN).coerceIn(0.0, 1.0))
        }
        val shelf = smooth(((roadGap(x, y) - ROAD_SHELF_START) / ROAD_SHELF).coerceIn(0.0, 1.0))
        val bytes = (body * shelf * bed).toInt()
        return if (bytes < MIN_DEPTH) 0 else minOf(bytes, MAX_DEPTH)
    }

    private fun roadGap(x: Int, y: Int): Double = road.gap(x, y)

    private fun islandGap(x: Int, y: Int, site: Site): Double {
        var gap = Double.MAX_VALUE
        for (island in islandsOf(site.cellX, site.cellY)) {
            val distance = hypot((x - island.x).toDouble(), (y - island.y).toDouble())
            if (distance > island.radius * ISLAND_REACH + ISLAND_SHELF) continue
            val reach = island.radius * (ISLAND_REACH_MIN + (ISLAND_REACH - ISLAND_REACH_MIN) * noise(x / ISLAND_WAVELENGTH, y / ISLAND_WAVELENGTH, ISLAND_SHAPE_SALT))
            gap = minOf(gap, distance - reach)
        }
        return gap
    }

    private fun liquid(biome: Biome, lake: Double, pond: Double, island: Boolean, crater: Double): Liquid? = when {
        biome == Biome.OCEAN -> if (island) null else Liquid.WATER
        biome == Biome.MOUNTAIN || biome == Biome.RUNE_ESSENCE -> null
        biome == Biome.VOLCANO -> if (crater > 0) Liquid.LAVA else null
        biome == Biome.DESERT && pond >= POND_LEVEL -> Liquid.WATER
        lake >= lakeLevel(biome) -> if (biome == Biome.WILDERNESS) Liquid.LAVA else Liquid.WATER
        else -> null
    }

    private fun liquidAtGlobal(x: Int, y: Int): Liquid? {
        val site = nearestSite(x, y)
        return liquid(site.biome, lake(x, y), pond(x, y), site.biome == Biome.OCEAN && islandLift(x, y, site) > 0, craterField(x, y, site))
    }

    private fun tileLiquid(points: Array<Array<Liquid?>>, x: Int, y: Int): Liquid? {
        val liquid = points[x][y] ?: return null
        return liquid.takeIf { points[x + 1][y] == it && points[x][y + 1] == it && points[x + 1][y + 1] == it }
    }

    private fun lakeLevel(biome: Biome) = when (biome) {
        Biome.WILDERNESS -> WILDERNESS_LAKE_LEVEL
        else -> LAKE_LEVEL
    }

    private fun ramp(field: Double, width: Double): Double = smooth((field / width).coerceIn(0.0, 1.0))

    private fun placeScenery(definition: MapDefinition, grid: Grid, regionX: Int, regionY: Int) {
        for (cellX in 0 until SIZE / CELL) {
            for (cellY in 0 until SIZE / CELL) {
                val hash = hash(regionX * CELLS + cellX, regionY * CELLS + cellY, SCENERY_SALT)
                val biome = grid.biomes[cellX * CELL][cellY * CELL]
                val options = sceneryOptions(biome, grid.heights[cellX * CELL][cellY * CELL])
                if (options.isEmpty() || hash % biome.odds != 0L) {
                    continue
                }
                val scenery = pick(options, hash shr SCENERY_PICK_SHIFT)
                val room = CELL - scenery.size + 1
                val localX = cellX * CELL + (hash / SCENERY_SPREAD % room).toInt()
                val localY = cellY * CELL + (hash / SCENERY_SPREAD / room % room).toInt()
                val globalX = regionX * SIZE + localX
                val globalY = regionY * SIZE + localY
                if (!inSector(globalX, globalY, globalX + scenery.size, globalY + scenery.size) || nearLiquid(globalX, globalY, scenery.size)) {
                    continue
                }
                if (roadTouchesGlobal(globalX - 1, globalY - 1, globalX + scenery.size, globalY + scenery.size)) {
                    continue
                }
                if (ranged(biome) && pathNear(globalX, globalY, scenery.size)) {
                    continue
                }
                if (overlapsFixtures(globalX, globalY, scenery.size, true) || overlapsClusters(globalX, globalY, scenery.size)) {
                    continue
                }
                definition.objects.add(MapObject(scenery.id, localX, localY, 0, scenery.shape, (hash shr ROTATION_SHIFT and 3).toInt()))
            }
        }
    }

    private fun sceneryOptions(biome: Biome, height: Int): List<Scenery> = when {
        biome != Biome.MOUNTAIN -> biome.scenery
        height >= SNOW_LINE -> MOUNTAIN_CREST_SCENERY
        height < FOOT_LINE -> MOUNTAIN_FOOT_SCENERY
        else -> MOUNTAIN_SLOPE_SCENERY
    }

    private fun overlapsClusters(x: Int, y: Int, size: Int): Boolean {
        for (cellX in cellOf(x) - 1..cellOf(x + size) + 1) {
            for (cellY in cellOf(y) - 1..cellOf(y + size) + 1) {
                for (cluster in clustersOf(cellX, cellY)) {
                    val half = cluster.side / 2
                    val x0 = cluster.centreX - half - SPOT_MARGIN
                    val y0 = cluster.centreY - half - SPOT_MARGIN
                    if (x < x0 + cluster.side + 2 * SPOT_MARGIN && x + size > x0 && y < y0 + cluster.side + 2 * SPOT_MARGIN && y + size > y0) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun placeFixtures(definition: MapDefinition, regionX: Int, regionY: Int) {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        for (cellX in cellOf(baseX) - 1..cellOf(baseX + SIZE - 1) + 1) {
            for (cellY in cellOf(baseY) - 1..cellOf(baseY + SIZE - 1) + 1) {
                for (fixture in fixturesOf(cellX, cellY) + stationOf(cellX, cellY) + islandsOf(cellX, cellY).flatMap { it.fixtures } + islandPlantsOf(cellX, cellY)) {
                    if (fixture.x in baseX until baseX + SIZE && fixture.y in baseY until baseY + SIZE) {
                        definition.objects.add(MapObject(fixture.id, fixture.x - baseX, fixture.y - baseY, 0, SCENERY_SHAPE, fixture.rotation))
                    }
                }
            }
        }
    }

    private fun placeOreClusters(definition: MapDefinition, regionX: Int, regionY: Int) {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        for (cellX in cellOf(baseX) - 1..cellOf(baseX + SIZE - 1) + 1) {
            for (cellY in cellOf(baseY) - 1..cellOf(baseY + SIZE - 1) + 1) {
                for (cluster in clustersOf(cellX, cellY)) {
                    for (index in cluster.rocks.indices) {
                        val x = cluster.globalX[index]
                        val y = cluster.globalY[index]
                        if (x in baseX until baseX + SIZE && y in baseY until baseY + SIZE) {
                            val rock = cluster.rocks[index]
                            definition.objects.add(MapObject(rock.id, x - baseX, y - baseY, 0, SCENERY_SHAPE, rock.rotation))
                        }
                    }
                }
            }
        }
    }

    private fun clustersOf(cellX: Int, cellY: Int): List<OreCluster> =
        clusters.computeIfAbsent(cellKey(cellX, cellY)) { findClusters(cellX, cellY) }

    private fun layouts(site: Site): List<Layout> = when (site.biome) {
        Biome.DESERT -> DESERT_ORES.flatMap { ore -> List(CLUSTERS_PER_ORE) { oreLayout(ore) } }
        Biome.WILDERNESS -> wildernessOres(hypot(site.x - ORIGIN_X, site.y - ORIGIN_Y)).map { oreLayout(it) }
        Biome.WINTER -> listOf(Layout(WINTER_ROCKS, WINTER_SIDE))
        else -> emptyList()
    }

    private fun oreLayout(ore: Int) = Layout(List(ROCKS_PER_CLUSTER) { ore }, CLUSTER_SIDE)

    private fun wildernessOres(distance: Double): List<Int> = when {
        distance < NEAR_TIER -> listOf(COAL_ROCK, IRON_ROCK, TIN_ROCK, COPPER_ROCK)
        distance < FAR_TIER -> listOf(MITHRIL_ROCK, MITHRIL_ROCK, ADAMANT_ROCK, ADAMANT_ROCK)
        else -> listOf(RUNE_ROCK, RUNE_ROCK)
    }

    private fun findClusters(cellX: Int, cellY: Int): List<OreCluster> {
        val site = site(cellX, cellY)
        val found = mutableListOf<OreCluster>()
        for ((number, layout) in layouts(site).withIndex()) {
            val hash = hash(cellX * CLUSTERS_STRIDE + number, cellY, CLUSTER_SALT)
            val spot = findSpot(site, hash, CLUSTER_START_RADIUS, CLUSTER_SEARCH_RINGS, CLUSTER_STEP) { x, y ->
                found.none { other -> abs(other.centreX - x) < CLUSTER_GAP && abs(other.centreY - y) < CLUSTER_GAP } &&
                    clusterSpotClear(site.biome, x, y, layout.side)
            } ?: continue
            found.add(buildCluster(layout, spot.first, spot.second, hash))
        }
        return found
    }

    private fun findSpot(site: Site, hash: Long, startRadius: Int, rings: Int, step: Int, clear: (Int, Int) -> Boolean): Pair<Int, Int>? {
        val spread = 2 * startRadius + 1
        val startX = site.x.toInt() - startRadius + (hash / SPOT_SPREAD % spread).toInt()
        val startY = site.y.toInt() - startRadius + (hash / SPOT_SPREAD / spread % spread).toInt()
        for (ring in 0..rings) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (maxOf(abs(dx), abs(dy)) != ring) continue
                    val x = startX + dx * step
                    val y = startY + dy * step
                    if (clear(x, y)) return x to y
                }
            }
        }
        return null
    }

    private fun clusterSpotClear(biome: Biome, centreX: Int, centreY: Int, side: Int): Boolean {
        val x0 = centreX - side / 2
        val y0 = centreY - side / 2
        if (!inSector(x0 - AREA_MARGIN, y0 - AREA_MARGIN, x0 + side + AREA_MARGIN, y0 + side + AREA_MARGIN)) return false
        for (x in x0 - AREA_MARGIN..x0 + side + AREA_MARGIN step AREA_STEP) {
            for (y in y0 - AREA_MARGIN..y0 + side + AREA_MARGIN step AREA_STEP) {
                if (biomeAtGlobal(x, y) != biome || liquidAtGlobal(x, y) != null) return false
            }
        }
        return !roadTouchesGlobal(x0 - AREA_MARGIN - 1, y0 - AREA_MARGIN - 1, x0 + side + AREA_MARGIN, y0 + side + AREA_MARGIN) &&
            !overlapsFixtures(x0, y0, side, false)
    }

    private fun buildCluster(layout: Layout, centreX: Int, centreY: Int, hash: Long): OreCluster {
        val side = layout.side
        val tiles = (0 until side * side).sortedBy { hash(it, layout.rocks.size, hash) }
        val chosen = mutableListOf<Int>()
        for (tile in tiles) {
            if (chosen.size == layout.rocks.size) break
            if (chosen.none { abs(it / side - tile / side) <= 1 && abs(it % side - tile % side) <= 1 }) chosen.add(tile)
        }
        val rocks = mutableListOf<MapObject>()
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for ((index, tile) in chosen.withIndex()) {
            rocks.add(MapObject(layout.rocks[index], 0, 0, 0, SCENERY_SHAPE, (hash shr ROTATION_SHIFT + index and 3).toInt()))
            xs.add(centreX - side / 2 + tile / side)
            ys.add(centreY - side / 2 + tile % side)
        }
        return OreCluster(rocks, xs, ys, centreX, centreY, side)
    }

    private fun fixturesOf(cellX: Int, cellY: Int): List<Fixture> =
        fixtures.computeIfAbsent(cellKey(cellX, cellY)) { findFixtures(cellX, cellY) }

    private fun findFixtures(cellX: Int, cellY: Int): List<Fixture> {
        val site = site(cellX, cellY)
        return when (site.biome) {
            Biome.WILDERNESS -> {
                val lodestone = findLodestone(site)
                val placed = listOfNotNull(lodestone, findFurnace(site, cellX, cellY, listOfNotNull(lodestone)))
                placed + listOfNotNull(findAltar(site, placed))
            }
            Biome.PLAINS -> findTrees(site, Loc.oaktree, PLAINS_OAKS, OAK_SIZE, OAK_SALT)
            Biome.DESERT -> findGemRocks(site)
            Biome.MOUNTAIN, Biome.VOLCANO -> rangeResources(site)
            Biome.RUNE_ESSENCE -> findTrees(site, MAGIC_TREE, MAGIC_TREES, MAGIC_SIZE, MAGIC_SALT, findMine(site))
            else -> emptyList()
        }
    }

    private fun findFurnace(site: Site, cellX: Int, cellY: Int, others: List<Fixture>): Fixture? {
        val hash = hash(cellX, cellY, FURNACE_SALT)
        val rotation = (hash and 3).toInt()
        val width = if (rotation and 1 == 0) FURNACE_WIDTH else FURNACE_DEPTH
        val depth = if (rotation and 1 == 0) FURNACE_DEPTH else FURNACE_WIDTH
        val spot = findSpot(site, hash, FURNACE_START_RADIUS, FURNACE_SEARCH_RADIUS, 1) { x, y -> spotClear(site.biome, x, y, width, depth, others) } ?: return null
        return Fixture(Loc.built_furnace, spot.first, spot.second, width, depth, rotation)
    }

    private fun findLodestone(site: Site): Fixture? {
        val hash = hash(site.cellX, site.cellY, LODESTONE_SALT)
        val spot = findSpot(site, hash, 0, LODESTONE_SEARCH_RINGS, LODESTONE_STEP) { x, y ->
            spotClear(site.biome, x - LODESTONE_RADIUS, y - LODESTONE_RADIUS, 2 * LODESTONE_RADIUS + 1, 2 * LODESTONE_RADIUS + 1, emptyList())
        } ?: return null
        return Fixture(Loc.wilderness_lodestone, spot.first, spot.second, 1, 1, 0, LODESTONE_RADIUS - SPOT_MARGIN)
    }

    fun lodestoneSites(): List<Pair<Int, Int>> {
        val sites = mutableListOf<Pair<Int, Int>>()
        for (cellX in cellOf(originX) - 1..cellOf(originX + WorldWindow.WINDOW_TILES - 1) + 1) {
            for (cellY in cellOf(originY) - 1..cellOf(originY + WorldWindow.WINDOW_TILES - 1) + 1) {
                if (site(cellX, cellY).biome != Biome.WILDERNESS) continue
                val lodestone = fixturesOf(cellX, cellY).firstOrNull { it.id == Loc.wilderness_lodestone } ?: continue
                val localX = lodestone.x - originX
                val localY = lodestone.y - originY
                if (localX in 0 until WorldWindow.WINDOW_TILES && localY in 0 until WorldWindow.WINDOW_TILES && inArea(localX / SIZE, localY / SIZE)) {
                    sites.add(localX to localY)
                }
            }
        }
        return sites
    }

    fun caveSites(): List<Pair<Int, Int>> {
        val sites = mutableListOf<Pair<Int, Int>>()
        for (cellX in cellOf(originX) - 1..cellOf(originX + WorldWindow.WINDOW_TILES - 1) + 1) {
            for (cellY in cellOf(originY) - 1..cellOf(originY + WorldWindow.WINDOW_TILES - 1) + 1) {
                val site = site(cellX, cellY)
                if (site.biome != Biome.WILDERNESS || !nearArea(site)) continue
                val cave = findCave(site) ?: continue
                val localX = cave.first - originX
                val localY = cave.second - originY
                val reach = CAVE_SIZE + CAVE_MARGIN
                if (localX - CAVE_MARGIN < 0 || localY - CAVE_MARGIN < 0 || localX + reach >= WorldWindow.WINDOW_TILES || localY + reach >= WorldWindow.WINDOW_TILES) continue
                if (inArea((localX - CAVE_MARGIN) / SIZE, (localY - CAVE_MARGIN) / SIZE) && inArea((localX + reach) / SIZE, (localY + reach) / SIZE)) {
                    sites.add(localX to localY)
                }
            }
        }
        return sites
    }

    private fun nearArea(site: Site): Boolean {
        if (areaTiles <= 0) return true
        val reach = areaTiles / 2 + BIOME_SPACING
        return abs(site.x - ORIGIN_X) <= reach && abs(site.y - ORIGIN_Y) <= reach
    }

    private fun findCave(site: Site): Pair<Int, Int>? {
        val hash = hash(site.cellX, site.cellY, CAVE_SALT)
        if (hash % PERCENT >= CAVE_PERCENT) return null
        val others = fixturesOf(site.cellX, site.cellY)
        val ores = clustersOf(site.cellX, site.cellY)
        val span = CAVE_SIZE + 2 * CAVE_MARGIN
        return findSpot(site, hash, CAVE_START_RADIUS, CAVE_SEARCH_RINGS, CAVE_STEP) { x, y ->
            spotClear(site.biome, x - CAVE_MARGIN, y - CAVE_MARGIN, span, span, others) &&
                ores.none { abs(it.centreX - x) < CLUSTER_GAP && abs(it.centreY - y) < CLUSTER_GAP } &&
                caveGroundFlat(x - CAVE_MARGIN, y - CAVE_MARGIN, span)
        }
    }

    private fun caveGroundFlat(x: Int, y: Int, span: Int): Boolean {
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (tx in x until x + span) for (ty in y until y + span) {
            val height = heightAt(tx - originX, ty - originY)
            lowest = minOf(lowest, height)
            highest = maxOf(highest, height)
        }
        return highest - lowest <= CAVE_MAX_SLOPE
    }

    private fun findTrees(site: Site, loc: Int, count: Int, size: Int, salt: Long, found: MutableList<Fixture> = mutableListOf()): List<Fixture> {
        for (number in 0 until count) {
            val hash = hash(site.cellX * FIXTURE_STRIDE + number, site.cellY, salt)
            val spot = findSpot(site, hash, OAK_START_RADIUS, OAK_SEARCH_RADIUS, 1) { x, y -> spotClear(site.biome, x, y, size, size, found) } ?: continue
            found.add(Fixture(loc, spot.first, spot.second, size, size, (hash shr ROTATION_SHIFT and 3).toInt()))
        }
        return found
    }

    private fun findAltar(site: Site, others: List<Fixture>): Fixture? {
        val hash = hash(site.cellX, site.cellY, ALTAR_SALT)
        val (loc, size) = when ((hash % PERCENT).toInt()) {
            in 0 until CHAOS_PERCENT -> CHAOS_ALTAR to ALTAR_SIZE
            in CHAOS_PERCENT until CHAOS_PERCENT + MIND_PERCENT -> MIND_ALTAR to ALTAR_SIZE
            in CHAOS_PERCENT + MIND_PERCENT until CHAOS_PERCENT + MIND_PERCENT + BLOOD_PERCENT -> BLOOD_ALTAR to BLOOD_ALTAR_SIZE
            else -> return null
        }
        val spot = findSpot(site, hash, ALTAR_START_RADIUS, ALTAR_SEARCH_RADIUS, 1) { x, y -> spotClear(site.biome, x, y, size, size, others) } ?: return null
        return Fixture(loc, spot.first, spot.second, size, size, 0)
    }

    private fun findMine(site: Site): MutableList<Fixture> {
        val hash = hash(site.cellX, site.cellY, MINE_SALT)
        val fixtures = mutableListOf<Fixture>()
        for ((number, chamber) in site.chambers.withIndex()) {
            val (cx, cy) = chamber
            fixtures.add(Fixture(ESSENCE_ROCK, cx - ESSENCE_SIZE / 2, cy - ESSENCE_SIZE / 2, ESSENCE_SIZE, ESSENCE_SIZE, 0, MINE_REACH))
            val inward = Math.atan2(site.y - cy, site.x - cx)
            for (step in 0 until MINE_RIM_ROCKS) {
                val angle = inward + (step + 0.5) * 2 * Math.PI / MINE_RIM_ROCKS
                val rock = MINE_RIM[((hash shr number + step) % MINE_RIM.size).toInt()]
                fixtures.add(Fixture(rock, cx + (cos(angle) * MINE_A * MINE_RIM_FIELD).roundToInt() - 1, cy + (sin(angle) * MINE_B * MINE_RIM_FIELD).roundToInt() - 1, 2, 2, step and 3))
            }
            fixtures.add(Fixture(Loc.di2_torch_snow, cx + (cos(inward) * MINE_A).roundToInt(), cy + (sin(inward) * MINE_B).roundToInt(), 1, 1, 0))
        }
        return fixtures
    }

    private fun findGemRocks(site: Site): List<Fixture> {
        val found = mutableListOf<Fixture>()
        for (number in 0 until DESERT_GEM_ROCKS) {
            val hash = hash(site.cellX * FIXTURE_STRIDE + number, site.cellY, GEM_SALT)
            val spot = findSpot(site, hash, GEM_START_RADIUS, GEM_SEARCH_RADIUS, 1) { x, y -> spotClear(site.biome, x, y, 1, 1, found) } ?: continue
            found.add(Fixture(GEM_ROCK, spot.first, spot.second, 1, 1, (hash shr ROTATION_SHIFT and 3).toInt()))
        }
        return found
    }

    private fun stationOf(cellX: Int, cellY: Int): List<Fixture> =
        stations.computeIfAbsent(cellKey(cellX, cellY)) { findStation(cellX, cellY) }

    private fun findStation(cellX: Int, cellY: Int): List<Fixture> {
        val site = site(cellX, cellY)
        if (site.biome != Biome.DESERT) {
            return emptyList()
        }
        val all = clustersOf(cellX, cellY)
        val anchor = all.firstOrNull() ?: return emptyList()
        val half = anchor.side / 2
        for (gap in half + 2..half + STATION_REACH step 2) {
            for (shift in -half..half step 2) {
                for (side in 0 until 4) {
                    val offsetX = if (side < 2) (if (side == 0) gap else -gap - FURNACE_WIDTH - STATION_GAP - 1) else shift
                    val offsetY = if (side < 2) shift else (if (side == 2) gap else -gap - FURNACE_DEPTH)
                    val furnace = Fixture(Loc.built_furnace, anchor.centreX + offsetX, anchor.centreY + offsetY, FURNACE_WIDTH, FURNACE_DEPTH, 0)
                    val anvil = Fixture(ANVIL, furnace.x + FURNACE_WIDTH + STATION_GAP, furnace.y + 1, 1, 1, 0)
                    if (stationClear(site, furnace, anvil, all)) {
                        return listOf(furnace, anvil)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun stationClear(site: Site, furnace: Fixture, anvil: Fixture, all: List<OreCluster>): Boolean {
        if (!spotClear(site.biome, furnace.x, furnace.y, furnace.width, furnace.depth, emptyList()) ||
            !spotClear(site.biome, anvil.x, anvil.y, 1, 1, emptyList())
        ) {
            return false
        }
        return all.none { cluster ->
            val half = cluster.side / 2
            val x0 = cluster.centreX - half - SPOT_MARGIN
            val y0 = cluster.centreY - half - SPOT_MARGIN
            val x1 = cluster.centreX + half + SPOT_MARGIN
            val y1 = cluster.centreY + half + SPOT_MARGIN
            furnace.x <= x1 && furnace.x + furnace.width > x0 && furnace.y <= y1 && furnace.y + furnace.depth > y0 ||
                anvil.x <= x1 && anvil.x + 1 > x0 && anvil.y <= y1 && anvil.y + 1 > y0
        }
    }

    private fun spotClear(biome: Biome, x: Int, y: Int, width: Int, depth: Int, others: List<Fixture>): Boolean {
        if (!inSector(x - SPOT_MARGIN, y - SPOT_MARGIN, x + width + SPOT_MARGIN, y + depth + SPOT_MARGIN)) {
            return false
        }
        for (dx in -SPOT_MARGIN until width + SPOT_MARGIN) {
            for (dy in -SPOT_MARGIN until depth + SPOT_MARGIN) {
                if (biomeAtGlobal(x + dx, y + dy) != biome) {
                    return false
                }
            }
        }
        return !roadTouchesGlobal(x - SPOT_MARGIN - 1, y - SPOT_MARGIN - 1, x + width + SPOT_MARGIN, y + depth + SPOT_MARGIN) &&
            !nearLiquid(x, y, maxOf(width, depth)) && others.none { overlaps(it, x, y, width, depth) }
    }

    private fun overlaps(fixture: Fixture, x: Int, y: Int, width: Int, depth: Int): Boolean =
        x < fixture.x + fixture.width + SPOT_MARGIN + fixture.reach && x + width > fixture.x - SPOT_MARGIN - fixture.reach &&
            y < fixture.y + fixture.depth + SPOT_MARGIN + fixture.reach && y + depth > fixture.y - SPOT_MARGIN - fixture.reach

    private fun overlapsFixtures(x: Int, y: Int, size: Int, withStations: Boolean): Boolean {
        for (cellX in cellOf(x) - 1..cellOf(x + size) + 1) {
            for (cellY in cellOf(y) - 1..cellOf(y + size) + 1) {
                if (fixturesOf(cellX, cellY).any { overlaps(it, x, y, size, size) }) {
                    return true
                }
                if (withStations && stationOf(cellX, cellY).any { overlaps(it, x, y, size, size) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun buildRange(site: Site): Range {
        if (site.biome == Biome.VOLCANO) return buildVolcano(site)
        val hash = hash(site.cellX, site.cellY, RANGE_SALT)
        val axis = fraction(hash, 0) * Math.PI
        val shift = ((hash shr RANGE_SHIFT) % PROFILES.size).toInt()
        val altarPeak = if ((hash shr ALTAR_SHIFT) % 2 == 0L) ((hash shr ALTAR_PEAK_SHIFT) % RANGE_PEAKS).toInt() else -1
        val peaks = List(RANGE_PEAKS) { index ->
            val roll = hash(site.cellX * RANGE_STRIDE + index, site.cellY, RANGE_SALT + 1)
            val along = (index - RANGE_PEAKS / 2) * RANGE_SPACING + (fraction(roll, 0) - 0.5) * 2 * RANGE_JITTER
            val across = (fraction(roll, 1) - 0.5) * 2 * RANGE_JITTER
            Peak(
                site.x + cos(axis) * along - sin(axis) * across,
                site.y + sin(axis) * along + cos(axis) * across,
                PEAK_MIN_RADIUS + fraction(roll, 2) * (PEAK_MAX_RADIUS - PEAK_MIN_RADIUS),
                PEAK_MIN_SCALE + fraction(roll, 3) * (1 - PEAK_MIN_SCALE)
            )
        }
        val trails = mutableListOf<Trail>()
        val clearings = mutableListOf<Clearing>()
        for ((index, peak) in peaks.withIndex()) {
            val (ascent, descent) = ways(peak, hash(site.cellX * RANGE_STRIDE + index, site.cellY, RANGE_SALT + 2), 0.0)
            trails.add(ascent)
            trails.add(descent)
            val profile = PROFILES[(index + shift) % PROFILES.size]
            profile.rocks.forEachIndexed { group, (rock, count) ->
                val at = pointAt(ascent, (group + 1.0) / (profile.rocks.size + 1))
                clearings.add(Clearing(at.first, at.second, ROCK_CLEARING_RADIUS, rock, count, 1))
            }
            if (profile.yews > 0) {
                val at = pointAt(descent, YEW_ALONG)
                clearings.add(Clearing(at.first, at.second, YEW_CLEARING_RADIUS, YEW_TREE, profile.yews, YEW_SIZE))
            }
            if (index == altarPeak) clearings.add(altarClearing(descent, EARTH_ALTAR))
        }
        return Range(peaks, trails, clearings)
    }

    private fun buildVolcano(site: Site): Range {
        val hash = hash(site.cellX, site.cellY, RANGE_SALT)
        val peak = Peak(site.x, site.y, VOLCANO_RADIUS, 1.0)
        val (ascent, descent) = ways(peak, hash, RIM_RADIUS)
        val rim = Trail(
            DoubleArray(RIM_STEPS + 1) { site.x + cos(2 * Math.PI * it / RIM_STEPS) * RIM_RADIUS },
            DoubleArray(RIM_STEPS + 1) { site.y + sin(2 * Math.PI * it / RIM_STEPS) * RIM_RADIUS }
        )
        val altar = if ((hash shr ALTAR_SHIFT) % 2 == 0L) listOf(altarClearing(descent, FIRE_ALTAR)) else emptyList()
        return Range(listOf(peak), listOf(ascent, rim, descent), altar)
    }

    private fun altarClearing(descent: Trail, loc: Int): Clearing {
        val at = pointAt(descent, ALTAR_ALONG)
        return Clearing(at.first, at.second, ALTAR_CLEARING_RADIUS, loc, 1, ALTAR_SIZE)
    }

    private fun ways(peak: Peak, roll: Long, top: Double): Pair<Trail, Trail> {
        val entrance = (roll and DIRECTION_MASK).toInt()
        val exit = (entrance + 1 + ((roll shr EXIT_SHIFT) % (DIRECTION_ANGLES.size - 1)).toInt()) % DIRECTION_ANGLES.size
        return trailOf(peak, DIRECTION_ANGLES[entrance], if (roll shr UP_SWIRL_SHIFT and 1L == 0L) PATH_SWIRL else -PATH_SWIRL, true, top) to
            trailOf(peak, DIRECTION_ANGLES[exit], if (roll shr DOWN_SWIRL_SHIFT and 1L == 0L) PATH_SWIRL else -PATH_SWIRL, false, top)
    }

    private fun trailOf(peak: Peak, angle: Double, swirl: Double, inward: Boolean, top: Double): Trail {
        val xs = DoubleArray(PATH_STEPS + 1)
        val ys = DoubleArray(PATH_STEPS + 1)
        for (step in 0..PATH_STEPS) {
            val along = step.toDouble() / PATH_STEPS
            val reach = top + (peak.radius * PATH_FOOT - top) * if (inward) 1 - along else along
            val turn = if (inward) swirl * along else -swirl * (1 - along)
            xs[step] = peak.x + cos(angle + turn) * reach
            ys[step] = peak.y + sin(angle + turn) * reach
        }
        return Trail(xs, ys)
    }

    private fun pointAt(trail: Trail, along: Double): Pair<Double, Double> {
        val position = along * (trail.xs.size - 1)
        val index = floor(position).toInt().coerceIn(0, trail.xs.size - 2)
        val fraction = position - index
        return lerp(trail.xs[index], trail.xs[index + 1], fraction) to lerp(trail.ys[index], trail.ys[index + 1], fraction)
    }

    private fun rangeResources(site: Site): List<Fixture> {
        val range = rangeOf(site)
        val found = mutableListOf<Fixture>()
        for (clearing in range.clearings) {
            val centreX = floor(clearing.x).toInt()
            val centreY = floor(clearing.y).toInt()
            val reach = ceil(clearing.radius).toInt()
            val candidates = mutableListOf<Pair<Int, Int>>()
            for (dx in -reach..reach) {
                for (dy in -reach..reach) {
                    if (resourceSpotClear(site.biome, range, clearing, centreX + dx, centreY + dy)) candidates.add(centreX + dx to centreY + dy)
                }
            }
            var placed = 0
            for ((x, y) in candidates.sortedBy { hash(it.first, it.second, RESOURCE_SALT) }) {
                if (placed == clearing.count) break
                if (found.any { overlaps(it, x, y, clearing.size, clearing.size) }) continue
                found.add(Fixture(clearing.loc, x, y, clearing.size, clearing.size, (hash(x, y, RESOURCE_SALT) shr ROTATION_SHIFT and 3).toInt()))
                placed++
            }
        }
        return found
    }

    private fun resourceSpotClear(biome: Biome, range: Range, clearing: Clearing, x: Int, y: Int): Boolean {
        if (!inSector(x - SPOT_MARGIN, y - SPOT_MARGIN, x + clearing.size + SPOT_MARGIN, y + clearing.size + SPOT_MARGIN)) return false
        for (dx in 0 until clearing.size) {
            for (dy in 0 until clearing.size) {
                val centreX = x + dx + HALF_TILE
                val centreY = y + dy + HALF_TILE
                if (hypot(centreX - clearing.x, centreY - clearing.y) > clearing.radius - 1 || range.gap(centreX, centreY) < RESOURCE_PATH_GAP) return false
                if (biomeAtGlobal(x + dx, y + dy) != biome) return false
            }
        }
        return !roadTouchesGlobal(x - 1, y - 1, x + clearing.size, y + clearing.size)
    }

    private fun islandPlantsOf(cellX: Int, cellY: Int): List<Fixture> =
        islandPlants.computeIfAbsent(cellKey(cellX, cellY)) { findPlants(cellX, cellY) }

    private fun islandArea(island: Island, site: Site): Int {
        val reach = ceil(island.radius * ISLAND_REACH).toInt() + 1
        var area = 0
        for (x in island.x - reach..island.x + reach) {
            for (y in island.y - reach..island.y + reach) {
                if (islandLift(x, y, site) > 0) area++
            }
        }
        return area
    }

    private fun findPlants(cellX: Int, cellY: Int): List<Fixture> {
        val site = site(cellX, cellY)
        val found = mutableListOf<Fixture>()
        for (island in islandsOf(cellX, cellY)) {
            if (islandArea(island, site) <= PLANT_MIN_AREA) continue
            val base = fraction(hash(island.x, island.y, PLANT_SALT), 0) * 2 * Math.PI
            val planted = mutableListOf<Fixture>()
            for (number in 0 until PLANTS_PER_ISLAND) {
                val spot = plantSpot(site, island, base + number * 2 * Math.PI / PLANTS_PER_ISLAND, planted) ?: continue
                planted.add(Fixture(RANARR_PLANT, spot.first, spot.second, 1, 1, 0))
            }
            found.addAll(planted)
        }
        return found
    }

    private fun plantSpot(site: Site, island: Island, angle: Double, planted: List<Fixture>): Pair<Int, Int>? {
        for (turn in 0 until PLANT_TURNS) {
            val rotated = angle + turn * PLANT_TURN
            var radius = island.radius * PLANT_START
            while (radius <= island.radius * ISLAND_REACH_MIN) {
                val x = (island.x + cos(rotated) * radius).roundToInt()
                val y = (island.y + sin(rotated) * radius).roundToInt()
                if (plantClear(site, island, x, y, planted)) return x to y
                radius += PLANT_STEP
            }
        }
        return null
    }

    private fun plantClear(site: Site, island: Island, x: Int, y: Int, planted: List<Fixture>): Boolean =
        islandLift(x, y, site) >= PLANT_LIFT && islandLift(x + 1, y + 1, site) >= PLANT_LIFT &&
            island.fixtures.none { overlaps(it, x, y, 1, 1) } && planted.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < PLANT_SPREAD }

    private fun islandSharksOf(cellX: Int, cellY: Int): List<Pair<Int, Int>> =
        islandSharks.computeIfAbsent(cellKey(cellX, cellY)) { findSharks(cellX, cellY) }

    private fun findSharks(cellX: Int, cellY: Int): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        for (island in islandsOf(cellX, cellY)) {
            val roll = hash(island.x, island.y, SHARK_SALT)
            val base = fraction(roll, 0) * 2 * Math.PI
            for (number in 0 until SHARKS_PER_ISLAND) {
                val spot = sharkSpot(island, base + number * Math.PI + (fraction(roll, 1 + number) - 0.5) * SHARK_SPREAD, found) ?: continue
                found.add(spot)
            }
        }
        return found
    }

    private fun sharkSpot(island: Island, angle: Double, taken: List<Pair<Int, Int>>): Pair<Int, Int>? {
        for (attempt in 0 until SHARK_ATTEMPTS) {
            val rotated = angle + attempt * SHARK_TURN
            var radius = island.radius * SHARK_START
            while (radius <= island.radius * ISLAND_REACH + SHARK_REACH) {
                val x = (island.x + cos(rotated) * radius).roundToInt()
                val y = (island.y + sin(rotated) * radius).roundToInt()
                if (tileCodeAt(x, y) == WATER_CODE && FOUR_NEIGHBOURS.any { (dx, dy) -> tileCodeAt(x + dx, y + dy) == LAND_CODE }) {
                    return (x to y).takeIf { spot -> taken.none { maxOf(abs(it.first - spot.first), abs(it.second - spot.second)) < SPOT_SEPARATION } }
                }
                radius += SHARK_STEP
            }
        }
        return null
    }

    private fun pointCode(x: Int, y: Int): Int {
        return when (liquidAtGlobal(x, y)) {
            Liquid.WATER -> WATER_CODE
            Liquid.LAVA -> LAVA_CODE
            null -> LAND_CODE
        }
    }

    private fun tileCodeAt(x: Int, y: Int): Int {
        val corner = pointCode(x, y)
        if (corner == LAND_CODE) return LAND_CODE
        return if (pointCode(x + 1, y) == corner && pointCode(x, y + 1) == corner && pointCode(x + 1, y + 1) == corner) corner else LAND_CODE
    }

    fun fishingSpotsIn(regionX: Int, regionY: Int): List<FishingSite> {
        val baseX = (originX / SIZE + regionX) * SIZE
        val baseY = (originY / SIZE + regionY) * SIZE
        val found = mutableListOf<FishingSite>()
        for (cellX in cellOf(baseX - FISHING_BODY_LIMIT)..cellOf(baseX + SIZE - 1 + FISHING_BODY_LIMIT)) {
            for (cellY in cellOf(baseY - FISHING_BODY_LIMIT)..cellOf(baseY + SIZE - 1 + FISHING_BODY_LIMIT)) {
                for (spot in fishingOf(cellX, cellY)) {
                    if (spot.x in baseX until baseX + SIZE && spot.y in baseY until baseY + SIZE) {
                        found.add(FishingSite(spot.x - originX, spot.y - originY, spot.kind))
                    }
                }
            }
        }
        return found
    }

    private fun fishingOf(cellX: Int, cellY: Int): List<FishingSite> =
        fishing.computeIfAbsent(cellKey(cellX, cellY)) { scanWater(cellX, cellY) }

    private fun fishingKind(x: Int, y: Int): FishingKind = when (hash(x, y, FISHING_KIND_SALT) % WATER_KINDS) {
        0L -> FishingKind.NET
        1L -> FishingKind.CAGE
        2L -> FishingKind.HARPOON
        else -> FishingKind.LURE
    }

    private fun spotsFor(area: Int): Int = when {
        area < FISHING_MIN_AREA -> 0
        area < FISHING_SMALL_AREA -> FISHING_SMALL_SPOTS
        area < FISHING_MEDIUM_AREA -> FISHING_MEDIUM_SPOTS
        area < FISHING_LARGE_AREA -> FISHING_LARGE_SPOTS
        area <= FISHING_BODY_LIMIT -> FISHING_BIG_SPOTS
        else -> FISHING_OCEAN_SPOTS
    }

    private fun tileKey(x: Int, y: Int): Long = x.toLong() shl CELL_KEY_SHIFT or (y.toLong() and CELL_KEY_MASK)

    private fun keyX(key: Long): Int = (key shr CELL_KEY_SHIFT).toInt()

    private fun keyY(key: Long): Int = key.toInt()

    private fun flood(startX: Int, startY: Int, limit: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, wet: (Int, Int) -> Boolean): List<Long> {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        val body = ArrayList<Long>()
        seen.add(tileKey(startX, startY))
        queue.add(tileKey(startX, startY))
        while (queue.isNotEmpty() && body.size < limit) {
            val key = queue.removeFirst()
            body.add(key)
            for ((dx, dy) in FOUR_NEIGHBOURS) {
                val x = keyX(key) + dx
                val y = keyY(key) + dy
                if (x < minX || y < minY || x > maxX || y > maxY || !wet(x, y)) continue
                if (seen.add(tileKey(x, y))) queue.add(tileKey(x, y))
            }
        }
        return body
    }

    private fun scanWater(cellX: Int, cellY: Int): List<FishingSite> {
        val x0 = cellX * BIOME_SPACING
        val y0 = cellY * BIOME_SPACING
        val side = BIOME_SPACING
        val points = Array(side + 1) { dx -> ByteArray(side + 1) { dy -> pointCode(x0 + dx, y0 + dy).toByte() } }
        val code = { x: Int, y: Int ->
            val i = x - x0
            val j = y - y0
            if (i in 0 until side && j in 0 until side) {
                val corner = points[i][j]
                if (corner != LAND_BYTE && points[i + 1][j] == corner && points[i][j + 1] == corner && points[i + 1][j + 1] == corner) corner.toInt() else LAND_CODE
            } else tileCodeAt(x, y)
        }
        val wet = { x: Int, y: Int -> code(x, y) == WATER_CODE }
        val visited = Array(side) { BooleanArray(side) }
        val inSector = { x: Int, y: Int -> x - originX in 0 until WorldWindow.WINDOW_TILES && y - originY in 0 until WorldWindow.WINDOW_TILES }
        val taken = mutableListOf<Long>()
        val spots = mutableListOf<FishingSite>()
        for ((x, y) in islandSharksOf(cellX, cellY)) {
            if (!inSector(x, y)) continue
            spots.add(FishingSite(x, y, FishingKind.SHARK))
            taken.add(tileKey(x, y))
        }
        for (i in 0 until side) {
            for (j in 0 until side) {
                if (visited[i][j] || !wet(x0 + i, y0 + j)) continue
                val body = flood(x0 + i, y0 + j, FISHING_BODY_LIMIT + 1, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, wet)
                val tiles: List<Long>
                val count: Int
                if (body.size <= FISHING_BODY_LIMIT) {
                    val owner = body.min()
                    tiles = body
                    count = if (keyX(owner) - x0 in 0 until side && keyY(owner) - y0 in 0 until side) spotsFor(body.size) else 0
                } else {
                    tiles = flood(x0 + i, y0 + j, Int.MAX_VALUE, x0, y0, x0 + side - 1, y0 + side - 1, wet)
                    count = spotsFor(tiles.size)
                }
                for (key in tiles) {
                    val tx = keyX(key) - x0
                    val ty = keyY(key) - y0
                    if (tx in 0 until side && ty in 0 until side) visited[tx][ty] = true
                }
                if (count == 0) continue
                val shores = tiles.filter { key ->
                    val x = keyX(key)
                    val y = keyY(key)
                    inSector(x, y) && FOUR_NEIGHBOURS.any { (dx, dy) -> code(x + dx, y + dy) == LAND_CODE }
                }
                for (key in chooseSpots(shores, count, taken)) {
                    spots.add(FishingSite(keyX(key), keyY(key), fishingKind(keyX(key), keyY(key))))
                    taken.add(key)
                }
            }
        }
        return spots
    }

    private fun chooseSpots(candidates: List<Long>, count: Int, taken: List<Long>): List<Long> {
        if (candidates.isEmpty()) return emptyList()
        val ordered = candidates.sortedBy { hash(keyX(it), keyY(it), FISHING_SALT) }
        val clusters = (count + FISHING_CLUSTER - 1) / FISHING_CLUSTER
        val chosen = mutableListOf<Long>()
        fun apart(key: Long, separation: Int) =
            chosen.none { maxOf(abs(keyX(it) - keyX(key)), abs(keyY(it) - keyY(key))) < separation } &&
                taken.none { maxOf(abs(keyX(it) - keyX(key)), abs(keyY(it) - keyY(key))) < SPOT_SEPARATION }
        for (separation in CLUSTER_SEPARATIONS) {
            for (key in ordered) {
                if (chosen.size == clusters) break
                if (apart(key, separation)) chosen.add(key)
            }
        }
        val anchors = chosen.toList()
        var round = 0
        while (chosen.size < count && round < count * FISHING_CLUSTER) {
            val anchor = anchors[round % anchors.size]
            round++
            val next = ordered.filter { apart(it, SPOT_SEPARATION) }.minByOrNull {
                hypot((keyX(it) - keyX(anchor)).toDouble(), (keyY(it) - keyY(anchor)).toDouble())
            } ?: continue
            chosen.add(next)
        }
        return chosen
    }

    private fun cellOf(tile: Int) = Math.floorDiv(tile, BIOME_SPACING)

    private fun islandsOf(cellX: Int, cellY: Int): List<Island> =
        islands.computeIfAbsent(cellKey(cellX, cellY)) { findIslands(cellX, cellY) }

    private fun findIslands(cellX: Int, cellY: Int): List<Island> {
        val site = site(cellX, cellY)
        if (site.biome != Biome.OCEAN) {
            return emptyList()
        }
        val count = ISLAND_MIN + (hash(cellX, cellY, ISLAND_SALT) % (ISLAND_MAX - ISLAND_MIN + 1)).toInt()
        val altar = hash(cellX, cellY, ALTAR_SALT)
        val found = mutableListOf<Island>()
        for (number in 0 until count) {
            for (attempt in 0 until ISLAND_ATTEMPTS) {
                val roll = hash(cellX * ISLAND_STRIDE + number, cellY * ISLAND_ATTEMPTS + attempt, ISLAND_SALT)
                val angle = fraction(roll, 0) * 2 * Math.PI
                val distance = fraction(roll, 1) * ISLAND_SCATTER
                val radius = ISLAND_MIN_RADIUS + fraction(roll, 2) * (ISLAND_MAX_RADIUS - ISLAND_MIN_RADIUS)
                val x = (site.x + cos(angle) * distance).toInt()
                val y = (site.y + sin(angle) * distance).toInt()
                if (!islandFits(site, x, y, radius, found)) continue
                found.add(Island(x, y, radius, islandFixtures(x, y, roll)))
                break
            }
        }
        if (altar % 2 == 0L && found.isNotEmpty()) {
            val index = ((altar shr ALTAR_PEAK_SHIFT) % found.size).toInt()
            found[index] = found[index].let { Island(it.x, it.y, it.radius, it.fixtures + Fixture(WATER_ALTAR, it.x - 1, it.y - 1, ALTAR_SIZE, ALTAR_SIZE, 0)) }
        }
        return found
    }

    private fun islandFits(site: Site, x: Int, y: Int, radius: Double, others: List<Island>): Boolean {
        if (nearestSite(x, y) !== site) return false
        val reach = (radius * ISLAND_REACH + ISLAND_CLEARANCE).toInt()
        if (!inSector(x - reach, y - reach, x + reach, y + reach)) return false
        if (fieldOf(distances(x, y), Biome.OCEAN) < radius * ISLAND_REACH + ISLAND_CLEARANCE) return false
        if (roadTouchesGlobal(x - ISLAND_CLEARANCE, y - ISLAND_CLEARANCE, x + ISLAND_CLEARANCE, y + ISLAND_CLEARANCE)) return false
        return others.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < it.radius + radius + ISLAND_GAP }
    }

    private fun islandFixtures(x: Int, y: Int, roll: Long): List<Fixture> {
        val rotation = (roll shr ROTATION_SHIFT and 3).toInt()
        val trees = mutableListOf(Fixture(MAPLE_TREE, x - 3, y - 3, MAPLE_SIZE, MAPLE_SIZE, rotation))
        if (roll shr ISLAND_TREE_SHIFT and 1L == 1L) {
            trees.add(Fixture(MAPLE_TREE, x + 2, y + 2, MAPLE_SIZE, MAPLE_SIZE, rotation))
        }
        trees.add(Fixture(Loc.boulder1, x - 3, y + 1, ISLAND_ROCK_SIZE, ISLAND_ROCK_SIZE, rotation))
        return trees
    }

    private fun islandLift(x: Int, y: Int, site: Site): Double {
        var best = 0.0
        for (island in islandsOf(site.cellX, site.cellY)) {
            val distance = hypot((x - island.x).toDouble(), (y - island.y).toDouble())
            if (distance > island.radius * ISLAND_REACH) continue
            val reach = island.radius * (ISLAND_REACH_MIN + (ISLAND_REACH - ISLAND_REACH_MIN) * noise(x / ISLAND_WAVELENGTH, y / ISLAND_WAVELENGTH, ISLAND_SHAPE_SALT))
            best = maxOf(best, 1 - distance / reach)
        }
        return best
    }

    private fun placeSmelting(definition: MapDefinition, grid: Grid) {
        val covered = Array(SIZE) { BooleanArray(SIZE) }
        for (x in 0 until SIZE - 1 step 2) {
            for (y in 0 until SIZE - 1 step 2) {
                val lava = grid.liquids[x][y]?.takeIf { it.smelt } ?: continue
                if (grid.liquids[x + 1][y] == lava && grid.liquids[x][y + 1] == lava && grid.liquids[x + 1][y + 1] == lava) {
                    definition.objects.add(MapObject(LAVA_SMELT_LARGE, x, y, 0, SCENERY_SHAPE, 0))
                    covered[x][y] = true
                    covered[x + 1][y] = true
                    covered[x][y + 1] = true
                    covered[x + 1][y + 1] = true
                }
            }
        }
        for (x in 0 until SIZE) {
            for (y in 0 until SIZE) {
                if (grid.liquids[x][y]?.smelt == true && !covered[x][y]) {
                    definition.objects.add(MapObject(LAVA_SMELT, x, y, 0, SCENERY_SHAPE, 0))
                }
            }
        }
    }

    private fun pick(options: List<Scenery>, roll: Long): Scenery {
        var remaining = (roll % options.sumOf { it.weight }).toInt()
        for (option in options) {
            remaining -= option.weight
            if (remaining < 0) {
                return option
            }
        }
        return options.last()
    }

    private fun nearLiquid(x: Int, y: Int, size: Int): Boolean {
        for (dx in -SHORE..size + SHORE) {
            for (dy in -SHORE..size + SHORE) {
                if (liquidAtGlobal(x + dx, y + dy) != null) {
                    return true
                }
            }
        }
        return false
    }

    private fun biomeAtGlobal(x: Int, y: Int): Biome = nearestSite(x, y).biome

    private fun warpedX(x: Int, y: Int) = x + (noise(x / WARP_WAVELENGTH, y / WARP_WAVELENGTH, WARP_SALT) - 0.5) * 2 * BIOME_WARP

    private fun warpedY(x: Int, y: Int) = y + (noise(x / WARP_WAVELENGTH, y / WARP_WAVELENGTH, WARP_SALT + 1) - 0.5) * 2 * BIOME_WARP

    private fun nearestSite(x: Int, y: Int): Site {
        val warpedX = warpedX(x, y)
        val warpedY = warpedY(x, y)
        val cellX = Math.floorDiv(warpedX.toInt(), BIOME_SPACING)
        val cellY = Math.floorDiv(warpedY.toInt(), BIOME_SPACING)
        var nearest = Double.MAX_VALUE
        var found = site(cellX, cellY)
        for (neighbourX in cellX - 1..cellX + 1) {
            for (neighbourY in cellY - 1..cellY + 1) {
                val site = site(neighbourX, neighbourY)
                val dx = (warpedX - site.x) / site.stretchX
                val dy = (warpedY - site.y) / site.stretchY
                val distance = dx * dx + dy * dy
                if (distance < nearest) {
                    nearest = distance
                    found = site
                }
            }
        }
        return found
    }

    private fun distances(x: Int, y: Int): DoubleArray {
        val warpedX = warpedX(x, y)
        val warpedY = warpedY(x, y)
        val cellX = Math.floorDiv(warpedX.toInt(), BIOME_SPACING)
        val cellY = Math.floorDiv(warpedY.toInt(), BIOME_SPACING)
        val result = DoubleArray(BIOMES) { Double.MAX_VALUE }
        for (neighbourX in cellX - 1..cellX + 1) {
            for (neighbourY in cellY - 1..cellY + 1) {
                val site = site(neighbourX, neighbourY)
                val dx = (warpedX - site.x) / site.stretchX
                val dy = (warpedY - site.y) / site.stretchY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < result[site.biome.ordinal]) {
                    result[site.biome.ordinal] = distance
                }
            }
        }
        return result
    }

    private fun fieldOf(distances: DoubleArray, biome: Biome): Double {
        val own = distances[biome.ordinal]
        var other = Double.MAX_VALUE
        for (index in distances.indices) {
            if (index != biome.ordinal && distances[index] < other) {
                other = distances[index]
            }
        }
        return when {
            own == Double.MAX_VALUE -> -FIELD_CAP
            other == Double.MAX_VALUE -> FIELD_CAP
            else -> ((other - own) / 2).coerceIn(-FIELD_CAP, FIELD_CAP)
        }
    }

    private fun site(cellX: Int, cellY: Int): Site = sites.computeIfAbsent(cellKey(cellX, cellY)) {
        val hash = hash(cellX, cellY, BIOME_SALT)
        Site(
            cellX, cellY,
            cellX * BIOME_SPACING + BIOME_SPACING / 2.0 + fraction(hash, 0) * 2 * BIOME_JITTER - BIOME_JITTER,
            cellY * BIOME_SPACING + BIOME_SPACING / 2.0 + fraction(hash, 1) * 2 * BIOME_JITTER - BIOME_JITTER,
            MIN_STRETCH + fraction(hash, 2) * (MAX_STRETCH - MIN_STRETCH),
            MIN_STRETCH + fraction(hash, 3) * (MAX_STRETCH - MIN_STRETCH),
            biomeOf(cellX, cellY, hash)
        )
    }

    private fun biomeOf(cellX: Int, cellY: Int, hash: Long): Biome {
        if (cellX == ORIGIN_CELL_X && cellY == ORIGIN_CELL_Y) return Biome.PLAINS
        if ((hash shr WINTER_PICK_SHIFT) % WINTER_SITE_ODDS == 0L) return Biome.WINTER
        val extra = hash(cellX, cellY, EXTRA_BIOME_SALT)
        val onRoadCell = abs(cellX - ORIGIN_CELL_X) <= ROAD_CELL_REACH || abs(cellY - ORIGIN_CELL_Y) <= ROAD_CELL_REACH
        return when {
            !onRoadCell && extra % OCEAN_SITE_ODDS == 0L -> Biome.OCEAN
            (extra shr MOUNTAIN_PICK_SHIFT) % MOUNTAIN_SITE_ODDS == 0L -> Biome.MOUNTAIN
            !onRoadCell && (extra shr VOLCANO_PICK_SHIFT) % VOLCANO_SITE_ODDS == 0L -> Biome.VOLCANO
            !onRoadCell && (extra shr RUNE_PICK_SHIFT) % RUNE_SITE_ODDS == 0L -> Biome.RUNE_ESSENCE
            else -> LAND_BIOMES[(hash shr BIOME_PICK_SHIFT).toInt().mod(LAND_BIOMES.size)]
        }
    }

    private fun cellKey(cellX: Int, cellY: Int): Long = cellX.toLong() shl CELL_KEY_SHIFT xor (cellY.toLong() and CELL_KEY_MASK)

    private fun fraction(hash: Long, index: Int): Double = (hash shr index * FRACTION_BITS and FRACTION_MASK) / (FRACTION_MASK + 1).toDouble()

    private fun lake(x: Int, y: Int): Double =
        (noise(x.toDouble() / LAKE_WAVELENGTH, y.toDouble() / LAKE_WAVELENGTH, LAKE_SALT) * LAKE_MAIN +
            noise(x.toDouble() / LAKE_DETAIL, y.toDouble() / LAKE_DETAIL, LAKE_SALT + 1) * (1 - LAKE_MAIN)) * originDry(x, y)

    private fun pond(x: Int, y: Int): Double = noise(x.toDouble() / POND_WAVELENGTH, y.toDouble() / POND_WAVELENGTH, POND_SALT) * originDry(x, y)

    private fun originDry(x: Int, y: Int): Double {
        val dx = (x - ORIGIN_X).toDouble()
        val dy = (y - ORIGIN_Y).toDouble()
        return if (dx * dx + dy * dy >= ORIGIN_DRY * ORIGIN_DRY) 1.0 else smooth(hypot(dx, dy) / ORIGIN_DRY)
    }

    private fun land(x: Int, y: Int): Double {
        var total = BASE_HEIGHT.toDouble()
        for ((wavelength, amplitude) in OCTAVES) {
            total += wave(x, y, wavelength, amplitude)
        }
        val flat = smooth((roadGap(x, y) / ROAD_FLAT).coerceIn(0.0, 1.0))
        for ((wavelength, amplitude) in BUMPS) {
            total += wave(x, y, wavelength, amplitude) * flat
        }
        return total
    }

    private fun wave(x: Int, y: Int, wavelength: Double, amplitude: Double): Double =
        (noise(x.toDouble() / wavelength, y.toDouble() / wavelength, HEIGHT_SALT) - 0.5) * 2 * amplitude

    private fun noise(x: Double, y: Double, salt: Long): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val top = lerp(lattice(x0, y0, salt), lattice(x0 + 1, y0, salt), fx)
        val bottom = lerp(lattice(x0, y0 + 1, salt), lattice(x0 + 1, y0 + 1, salt), fx)
        return lerp(top, bottom, fy)
    }

    private fun lattice(x: Int, y: Int, salt: Long): Double = (hash(x, y, salt) % LATTICE_STEPS) / LATTICE_STEPS.toDouble()

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun hash(x: Int, y: Int, salt: Long): Long {
        var h = seed xor salt
        h = (h xor x.toLong()) * MIX_A
        h = (h xor (h ushr 29) xor y.toLong()) * MIX_B
        h = h xor (h ushr 32)
        return h and Long.MAX_VALUE
    }

    private fun row(tile: (Int) -> String): String = List(SIZE) { tile(it) }.joinToString(" ")

    private fun rows(tile: (Int) -> String): String =
        List(SIZE) { "      \"${tile(it)}\"" }.joinToString(",\n", prefix = "    [\n", postfix = "\n    ]")

    companion object {
        const val DEFAULT_SEED = 727L
        const val BIOME_SPACING = 340
        private const val CITY_BLEND = 12
        private const val UPPER_LEVELS = 3
        private const val UPPER_FLOOR_HEIGHT = 31
        private const val ROOFED_TILE = 4
        private const val FLOOR_LEVEL_SHIFT = 12
        private const val FLOOR_AXIS_SHIFT = 6
        const val ORIGIN_X = WorldWindow.WORLD_ORIGIN_X
        const val ORIGIN_Y = WorldWindow.WORLD_ORIGIN_Y
        private const val ORIGIN_CELL_X = ORIGIN_X / BIOME_SPACING
        private const val ORIGIN_CELL_Y = ORIGIN_Y / BIOME_SPACING
        const val MIN_BIOME_WIDTH = 200
        const val MAX_BIOME_WIDTH = 400
        const val PLAINS_UNDERLAY = Underlay.underlay_161
        const val DESERT_UNDERLAY = Underlay.underlay_62
        const val FOREST_UNDERLAY = Underlay.underlay_160
        const val WILDERNESS_UNDERLAY = Underlay.underlay_149
        const val WINTER_UNDERLAY = Underlay.underlay_58
        const val OCEAN_UNDERLAY = Underlay.underlay_161
        const val MOUNTAIN_UNDERLAY = Underlay.underlay_44
        const val TRAIL_UNDERLAY = Underlay.underlay_66
        const val VOLCANO_UNDERLAY = Underlay.underlay_145
        private const val ASH_UNDERLAY = VOLCANO_UNDERLAY
        private const val OBSIDIAN_UNDERLAY = Underlay.underlay_108
        private const val EMBER_UNDERLAY = Underlay.underlay_121
        private const val BEACH_UNDERLAY = Underlay.underlay_61
        const val RUNE_ESSENCE_UNDERLAY = Underlay.underlay_58
        private const val MINE_FLOOR_UNDERLAY = Underlay.underlay_55
        private const val MINE_RING_UNDERLAY = Underlay.underlay_26
        private const val SAND_BED_UNDERLAY = Underlay.underlay_37
        private const val MUD_BED_UNDERLAY = Underlay.underlay_65
        private const val DEEP_BED_UNDERLAY = Underlay.underlay_55
        private const val HIDDEN_OVERLAY = Overlay.overlay_42
        private const val WINTER_SITE_ODDS = 8L
        private const val WINTER_PICK_SHIFT = 30
        private const val OCEAN_SITE_ODDS = 6L
        private const val MOUNTAIN_SITE_ODDS = 7L
        private const val VOLCANO_SITE_ODDS = 9L
        private const val MOUNTAIN_PICK_SHIFT = 20
        private const val VOLCANO_PICK_SHIFT = 40
        private const val EXTRA_BIOME_SALT = 0x42494F32L
        private val LAND_BIOMES = listOf(Biome.PLAINS, Biome.DESERT, Biome.FOREST, Biome.WILDERNESS)
        private val DRY_BIOMES = setOf(Biome.OCEAN, Biome.MOUNTAIN, Biome.VOLCANO, Biome.RUNE_ESSENCE)
        private const val RUNE_SITE_ODDS = 9L
        private const val RUNE_PICK_SHIFT = 10
        private val BIOMES = Biome.entries.size
        const val ROAD_OVERLAY = Overlay.overlay_102
        const val ROAD_WIDTH = RoadNetwork.WIDTH
        const val WATER_OVERLAY = Overlay.overlay_112
        const val WATER_UNDERLAY = Underlay.underlay_37
        const val LAVA_OVERLAY = Overlay.overlay_19
        const val LAVA_SMELT = Loc.lava_smelt
        const val LAVA_SMELT_LARGE = Loc.lava_smelt_large
        private const val PLAINS_ODDS = 5L
        private const val DESERT_ODDS = 14L
        private const val FOREST_ODDS = 2L
        private const val WILDERNESS_ODDS = 9L
        private const val WINTER_ODDS = 9L
        private const val MOUNTAIN_ODDS = 8L
        private const val VOLCANO_ODDS = 8L
        private const val RUNE_ESSENCE_ODDS = 6L
        private const val CLUSTERS_STRIDE = 16
        private const val CLUSTER_SIDE = 20
        private const val ROCKS_PER_CLUSTER = 10
        private const val CLUSTERS_PER_ORE = 2
        private const val CLUSTER_GAP = 40
        private const val CLUSTER_START_RADIUS = 80
        private const val CLUSTER_SEARCH_RINGS = 40
        private const val CLUSTER_STEP = 3
        private const val AREA_MARGIN = 2
        private const val AREA_STEP = 2
        private const val CLUSTER_SALT = 0x4F5245L
        private const val WINTER_SIDE = 8
        private const val NEAR_TIER = 1000.0
        private const val FAR_TIER = 3000.0
        private const val COAL_ROCK = Loc.loc_11930
        private const val IRON_ROCK = Loc.loc_11954
        private const val TIN_ROCK = Loc.loc_11933
        private const val COPPER_ROCK = Loc.loc_11936
        private const val CLAY_ROCK = Loc.loc_11189
        private const val MITHRIL_ROCK = Loc.loc_11942
        private const val ADAMANT_ROCK = Loc.loc_11939
        private const val RUNE_ROCK = Loc.loc_37208
        private const val GEM_ROCK = Loc.loc_11194
        private const val ANVIL = Loc.loc_2783
        private const val MAPLE_TREE = Loc.loc_1307
        private const val MAGIC_TREE = Loc.loc_1306
        private const val ESSENCE_ROCK = Loc.loc_2491
        private const val MIND_ALTAR = Loc.loc_2479
        private const val WATER_ALTAR = Loc.loc_2480
        private const val EARTH_ALTAR = Loc.loc_2481
        private const val FIRE_ALTAR = Loc.loc_2482
        private const val CHAOS_ALTAR = Loc.loc_2487
        private const val BLOOD_ALTAR = Loc.loc_30624
        private val MINE_RIM = intArrayOf(Loc.di2_snow_rune_rocks_big1, Loc.di2_snow_rune_rocks_big2, Loc.di2_snow_rune_rocks_big3, Loc.di2_snow_rune_rocks_big4)
        private val DESERT_ORES = listOf(CLAY_ROCK, IRON_ROCK, TIN_ROCK, COPPER_ROCK)
        private val WINTER_ROCKS = listOf(MITHRIL_ROCK, MITHRIL_ROCK, COAL_ROCK, COAL_ROCK)
        private const val FURNACE_WIDTH = 2
        private const val FURNACE_DEPTH = 3
        private const val FURNACE_START_RADIUS = 60
        private const val FURNACE_SEARCH_RADIUS = 80
        private const val FURNACE_SALT = 0x46524EL
        const val LODESTONE_RADIUS = 10
        private const val LODESTONE_SEARCH_RINGS = 40
        private const val LODESTONE_STEP = 2
        private const val LODESTONE_SALT = 0x4C4F44L
        private const val STATION_GAP = 1
        private const val STATION_REACH = 16
        private const val SPOT_MARGIN = 1
        private const val SPOT_SPREAD = 7L
        private const val FIXTURE_STRIDE = 8
        private const val PLAINS_OAKS = 3
        private const val MAGIC_TREES = 2
        private const val MAGIC_SIZE = 2
        private const val MAGIC_SALT = 0x4D4147L
        private const val ESSENCE_SIZE = 5
        private const val ALTAR_SIZE = 3
        private const val BLOOD_ALTAR_SIZE = 4
        private const val PERCENT = 100
        private const val CHAOS_PERCENT = 20
        private const val MIND_PERCENT = 20
        private const val BLOOD_PERCENT = 5
        private const val ALTAR_START_RADIUS = 90
        private const val ALTAR_SEARCH_RADIUS = 80
        private const val ALTAR_SALT = 0x414C54L
        const val CAVE_SIZE = 4
        const val CAVE_MARGIN = 3
        private const val CAVE_PERCENT = 33
        private const val CAVE_START_RADIUS = 70
        private const val CAVE_SEARCH_RINGS = 40
        private const val CAVE_STEP = 2
        private const val CAVE_MAX_SLOPE = 2
        private const val CAVE_SALT = 0x43415645L
        private const val ALTAR_SHIFT = 16
        private const val ALTAR_PEAK_SHIFT = 24
        private const val ALTAR_ALONG = 0.8
        private const val ALTAR_CLEARING_RADIUS = 8.0
        private const val MINE_SALT = 0x4D494EL
        private const val MINE_DX = 16
        private const val MINE_DY = 14
        private const val MINE_A = 12.0
        private const val MINE_B = 9.0
        private const val MINE_FLOOR = 0.55
        private const val MINE_RING = 0.8
        private const val MINE_FLAT_SPAN = 0.8
        private const val MINE_DEPTH = 8.0
        private const val MINE_REACH = 8
        private const val MINE_RIM_ROCKS = 8
        private const val MINE_RIM_FIELD = 1.12
        private const val RUNE_PATCH_WAVELENGTH = 26.0
        private const val RUNE_PATCH_SALT = 0x525054L
        private const val RUNE_PATCH_SHARE = 0.3
        private const val OAK_SIZE = 3
        private const val OAK_START_RADIUS = 90
        private const val OAK_SEARCH_RADIUS = 80
        private const val OAK_SALT = 0x4F414BL
        private const val DESERT_GEM_ROCKS = 2
        private const val GEM_START_RADIUS = 90
        private const val GEM_SEARCH_RADIUS = 80
        private const val GEM_SALT = 0x47454DL
        private const val RANGE_PEAKS = 3
        private const val RANGE_STRIDE = 8
        private const val RANGE_SPACING = 58.0
        private const val RANGE_JITTER = 8.0
        private const val PEAK_MIN_RADIUS = 52.0
        private const val PEAK_MAX_RADIUS = 64.0
        private const val PEAK_MIN_SCALE = 0.88
        private const val RANGE_LIFT = 150.0
        private const val RANGE_BORDER_FADE = 30.0
        private const val BODY_THRESHOLD = 0.18
        private const val PATH_STEPS = 12
        private const val PATH_FOOT = 0.92
        private const val PATH_SWIRL = 1.3
        private const val PATH_HALF_WIDTH = 2.0
        private const val SUMMIT_RADIUS = 4.5
        private const val CLIFF_PATH_CLEARANCE = 5.0
        private const val HALF_TILE = 0.5
        private const val ROCK_CLEARING_RADIUS = 7.0
        private const val YEW_CLEARING_RADIUS = 9.0
        private const val YEW_ALONG = 0.5
        private const val YEW_SIZE = 3
        private const val RESOURCE_PATH_GAP = 3.0
        private const val RANGE_SALT = 0x52414E47L
        private const val RESOURCE_SALT = 0x524553L
        private const val RANGE_SHIFT = 12
        private const val DIRECTION_MASK = 3L
        private const val EXIT_SHIFT = 4
        private const val UP_SWIRL_SHIFT = 8
        private const val DOWN_SWIRL_SHIFT = 9
        private val DIRECTION_ANGLES = doubleArrayOf(Math.PI / 2, -Math.PI / 2, Math.PI, 0.0)
        private const val YEW_TREE = Loc.yewtree
        private const val RANARR_PLANT = Loc.ranarr_plant
        private val PROFILES = listOf(
            Profile(listOf(COAL_ROCK to 4, IRON_ROCK to 2, TIN_ROCK to 3), 0),
            Profile(listOf(COAL_ROCK to 3, IRON_ROCK to 2, CLAY_ROCK to 3), 1),
            Profile(listOf(COAL_ROCK to 3, IRON_ROCK to 1, COPPER_ROCK to 3), 1)
        )
        private const val PLANT_MIN_AREA = 10
        private const val PLANTS_PER_ISLAND = 3
        private const val PLANT_START = 0.35
        private const val PLANT_STEP = 0.5
        private const val PLANT_LIFT = 0.25
        private const val PLANT_TURNS = 8
        private const val PLANT_TURN = 0.4
        private const val PLANT_SPREAD = 6.0
        private const val PLANT_SALT = 0x504C41L
        private const val SHARKS_PER_ISLAND = 2
        private const val SHARK_START = 0.5
        private const val SHARK_STEP = 0.25
        private const val SHARK_REACH = 6.0
        private const val SHARK_SPREAD = 1.0
        private const val SHARK_TURN = 0.35
        private const val SHARK_ATTEMPTS = 8
        private const val SHARK_SALT = 0x534852L
        private const val LAND_CODE = 0
        private const val WATER_CODE = 1
        private const val LAVA_CODE = 2
        private const val LAND_BYTE = 0.toByte()
        private val FOUR_NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private const val WATER_KINDS = 4L
        private const val FISHING_MIN_AREA = 10
        private const val FISHING_SMALL_AREA = 30
        private const val FISHING_MEDIUM_AREA = 60
        private const val FISHING_LARGE_AREA = 100
        private const val FISHING_BODY_LIMIT = 200
        private const val FISHING_SMALL_SPOTS = 3
        private const val FISHING_MEDIUM_SPOTS = 6
        private const val FISHING_LARGE_SPOTS = 10
        private const val FISHING_BIG_SPOTS = 14
        private const val FISHING_OCEAN_SPOTS = 20
        private const val FISHING_CLUSTER = 3
        private const val SPOT_SEPARATION = 2
        private val CLUSTER_SEPARATIONS = listOf(12, 6, 3, 1)
        private const val FISHING_SALT = 0x46495348L
        private const val FISHING_KIND_SALT = 0x46494B4EL
        private const val ISLAND_MIN = 3
        private const val ISLAND_MAX = 4
        private const val ISLAND_ATTEMPTS = 60
        private const val ISLAND_STRIDE = 8
        private const val ISLAND_SCATTER = 90.0
        private const val ISLAND_MIN_RADIUS = 9.0
        private const val ISLAND_MAX_RADIUS = 12.0
        private const val ISLAND_REACH_MIN = 0.8
        private const val ISLAND_REACH = 1.2
        private const val ISLAND_CLEARANCE = 24
        private const val ISLAND_GAP = 30.0
        private const val ISLAND_PEAK = 12
        private const val ISLAND_SLOPE = 0.5
        private const val ISLAND_WAVELENGTH = 9.0
        private const val ISLAND_TREE_SHIFT = 44
        private const val ISLAND_ROCK_SIZE = 2
        private const val MAPLE_SIZE = 2
        private const val ISLAND_SALT = 0x49534CL
        private const val ISLAND_SHAPE_SALT = 0x49534DL
        private const val SCENERY_SHAPE = 10
        private const val GROUND_DECOR_SHAPE = 22
        private const val CELL = 8
        private const val CELLS = 8
        private const val SCENERY_SPREAD = 14L
        private const val SCENERY_PICK_SHIFT = 20
        private const val ROTATION_SHIFT = 40
        private const val SCENERY_SALT = 0x7452EEL
        private const val HEIGHT_SALT = 0x4854L
        private const val LAKE_SALT = 0x4C414BL
        private const val POND_SALT = 0x504F4EL
        private const val BIOME_SALT = 0x42494FL
        private const val WARP_SALT = 0x57415250L
        private const val MOUNTAIN_SALT = 0x4D4F554EL
        private const val BIOME_JITTER = 40.0
        private const val BIOME_WARP = 30.0
        private const val WARP_WAVELENGTH = 90.0
        private const val MIN_STRETCH = 0.85
        private const val MAX_STRETCH = 1.15
        private const val FRACTION_BITS = 12
        private const val FRACTION_MASK = (1L shl FRACTION_BITS) - 1
        private const val BIOME_PICK_SHIFT = 50
        private const val CELL_KEY_SHIFT = 32
        private const val CELL_KEY_MASK = 0xFFFFFFFFL
        private const val MIX_A = -0x61c8864680b583ebL
        private const val MIX_B = -0x3b314601e57a13adL
        private const val LATTICE_STEPS = 65536L
        private const val BASE_HEIGHT = 40
        private const val WATER_HEIGHT = 2
        private const val MAX_HEIGHT = 255
        private const val LAKE_WAVELENGTH = 110.0
        private const val LAKE_DETAIL = 34.0
        private const val LAKE_MAIN = 0.75
        private const val LAKE_LEVEL = 0.69
        private const val WILDERNESS_LAKE_LEVEL = 0.71
        private const val BANK_START = 0.55
        private const val POND_WAVELENGTH = 40.0
        private const val POND_LEVEL = 0.86
        private const val POND_BANK_START = 0.72
        private const val SHORE = 2
        private const val SHORE_BLEND = 24.0
        private const val FIELD_CAP = 200.0
        private const val MOUNTAIN_PEAKS = 30.0
        private const val FOOT_LINE = 85
        private const val SNOW_LINE = 155
        private const val CLIFF_WAVELENGTH = 64.0
        private const val CLIFF_EDGE = 0.025
        private const val CLIFF_STEP = 14.0
        private const val CLIFF_SALT = 0x434C4946L
        private val CLIFF_LEVELS = listOf(0.4, 0.55, 0.7)
        private const val CRATER_RADIUS = 26.0
        private const val CRATER_BANK = 10.0
        private const val EMBER_RING = 14.0
        private const val ASH_WAVELENGTH = 18.0
        private const val ASH_SALT = 0x415348L
        private const val OBSIDIAN_SHARE = 0.3
        private const val ROAD_FLAT = 12.0
        private const val ORIGIN_DRY = 100.0
        private const val ROAD_CELL_REACH = 1
        private const val ROAD_SALT = 0x524F4144L
        private const val ROAD_SHORE_BASIN = 0.4
        private const val ISLAND_BUMP = 4.0
        private const val ISLAND_BUMP_WAVELENGTH = 14.0
        private const val BED_WAVELENGTH = 30.0
        private const val BED_VARIATION = 0.3
        private const val BED_SALT = 0x424544L
        private const val WATER_TILE_HEIGHT = 1
        private const val BEACH_HEIGHT = 5
        private const val OCEAN_DEPTH = 240.0
        private const val OCEAN_SLOPE = 90.0
        private const val ISLAND_SHELF = 60.0
        private const val LAKE_DEPTH = 64.0
        private const val LAKE_SPAN = 0.1
        private const val POND_DEPTH = 22.0
        private const val POND_SPAN = 0.06
        private const val ROAD_SHELF_START = 2.0
        private const val ROAD_SHELF = 24.0
        private const val MIN_DEPTH = 2
        private const val MAX_DEPTH = 255
        private const val SAND_DEPTH = 8
        private const val MUD_DEPTH = 34
        private const val GROUND_LEVEL = 0
        private const val MOUNTAIN_PEAK_WAVELENGTH = 50.0
        private const val VOLCANO_RADIUS = 100.0
        private const val VOLCANO_LIFT = 200.0
        private const val CRATER_DEPTH = 50.0
        private const val RIM_RADIUS = 38.0
        private const val RIM_STEPS = 32
        private val OCTAVES = listOf(160.0 to 34.0, 48.0 to 12.0, 12.0 to 3.0)
        private val BUMPS = listOf(70.0 to 14.0, 28.0 to 8.0)
        private const val VOID_TILE = "-"
        private const val SIZE = 64
        private const val GRID_CACHE_LIMIT = 64
        private const val TEXT_CAPACITY = 64 * 1024
        private const val EFFECTS = """[
    {"type": "environment", "sunColour": 14536379, "sunAmbient": 334, "sunLight": 512, "sunBacklight": 92, "sunPosition": [-4, -16, 1], "fogColour": 9282754, "fogDepth": 600, "cubeTexture": [682, 683, 684, 685, 686, 687]},
    {"type": "hdr", "bloom": 32, "brightpass": 8, "whitePoint": 32}
  ]"""
    }
}

private val MOUNTAIN_FOOT_SCENERY = listOf(
    WorldGenerator.Scenery(Loc.loc_1315, 2, 4), WorldGenerator.Scenery(Loc.loc_1316, 3, 3),
    WorldGenerator.Scenery(Loc.loc_1318, 3, 3), WorldGenerator.Scenery(Loc.loc_1319, 3, 3),
    WorldGenerator.Scenery(Loc.boulder1, 2, 1), WorldGenerator.Scenery(Loc.boulder3, 1, 2)
)

private val MOUNTAIN_SLOPE_SCENERY = listOf(
    WorldGenerator.Scenery(Loc.loc_21273, 2, 3), WorldGenerator.Scenery(Loc.loc_1315, 2, 2),
    WorldGenerator.Scenery(Loc.snowtree1, 3, 1), WorldGenerator.Scenery(Loc.snowtree2, 3, 1),
    WorldGenerator.Scenery(Loc.ra4_troll_area_big_rock_no_op, 3, 2), WorldGenerator.Scenery(Loc.overhang3rock, 4, 1),
    WorldGenerator.Scenery(Loc.rocko_prison_sharp_rock01, 1, 2), WorldGenerator.Scenery(Loc.boulder1, 2, 2),
    WorldGenerator.Scenery(Loc.boulder2, 2, 2), WorldGenerator.Scenery(Loc.boulder4, 1, 2)
)

private val MOUNTAIN_CREST_SCENERY = listOf(
    WorldGenerator.Scenery(Loc.ra4_troll_area_big_rock_no_op, 3, 3), WorldGenerator.Scenery(Loc.overhang3rock, 4, 2),
    WorldGenerator.Scenery(Loc.stalagmite_twin_grey, 1, 3), WorldGenerator.Scenery(Loc.stagamite_small_grey, 1, 3),
    WorldGenerator.Scenery(Loc.mah1_deadtree1_large_snow, 2, 2), WorldGenerator.Scenery(Loc.rocko_prison_sharp_rock01, 1, 3),
    WorldGenerator.Scenery(Loc.death_mountainrocks_1, 1, 3), WorldGenerator.Scenery(Loc.boulder1, 2, 2),
    WorldGenerator.Scenery(Loc.boulder3, 1, 3)
)
