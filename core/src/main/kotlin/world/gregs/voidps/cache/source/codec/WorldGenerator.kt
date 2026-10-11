package world.gregs.voidps.cache.source.codec

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Overlay
import org.darkan.core.gameval.gen.Underlay
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class WorldGenerator(
    private val seed: Long = DEFAULT_SEED,
    private val originX: Int = 0,
    private val originY: Int = 0,
    private val areaTiles: Int = 0
) : MapGenerator {

    fun inArea(regionX: Int, regionY: Int): Boolean {
        if (areaTiles <= 0) return true
        val span = (areaTiles + SIZE - 1) / SIZE
        val lowX = maxOf(0, ORIGIN_X / SIZE - span / 2)
        val lowY = maxOf(0, ORIGIN_Y / SIZE - span / 2)
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
        )
    }

    enum class Liquid(val overlay: Int, val underlay: Int, val smelt: Boolean) {
        WATER(WATER_OVERLAY, WATER_UNDERLAY, false),
        LAVA(LAVA_OVERLAY, WILDERNESS_UNDERLAY, true)
    }

    private class Site(val cellX: Int, val cellY: Int, val x: Double, val y: Double, val stretchX: Double, val stretchY: Double, val biome: Biome)

    private class Fixture(val id: Int, val x: Int, val y: Int, val width: Int, val depth: Int, val rotation: Int, val reach: Int = 0)

    private class Island(val x: Int, val y: Int, val radius: Double, val fixtures: List<Fixture>)

    private class Layout(val rocks: List<Int>, val side: Int)

    private class OreCluster(val rocks: List<MapObject>, val globalX: List<Int>, val globalY: List<Int>, val centreX: Int, val centreY: Int, val side: Int)

    private class Grid(
        val biomes: Array<Array<Biome>>,
        val liquids: Array<Array<Liquid?>>,
        val heights: Array<IntArray>,
        val underlays: Array<IntArray>,
        val roads: Array<BooleanArray>,
        val depths: Array<IntArray>?
    )

    private val dryRows: String by lazy { rows { _ -> row { _ -> VOID_TILE } } }

    private val sites = ConcurrentHashMap<Long, Site>()

    private val grids = ConcurrentHashMap<Long, Grid>()

    private val fixtures = ConcurrentHashMap<Long, List<Fixture>>()

    private val stations = ConcurrentHashMap<Long, List<Fixture>>()

    private val islands = ConcurrentHashMap<Long, List<Island>>()

    private val clusters = ConcurrentHashMap<Long, List<OreCluster>>()

    override fun terrain(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
        val grid = grid(originX / SIZE + regionX, originY / SIZE + regionY)
        val void = rows { _ -> row { _ -> VOID_TILE } }
        val surface = rows { localX -> row { localY -> tile(grid, localX, localY) } }
        val text = StringBuilder(TEXT_CAPACITY)
        text.append("{\n  \"region\": \"").append(square.directory).append("\",\n  \"levels\": 4,\n  \"tiles\": [\n")
        text.append(surface).append(",\n").append(void).append(",\n").append(void).append(",\n").append(void)
        text.append("\n  ],\n  \"effects\": ").append(EFFECTS).append("\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

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

    fun onRoad(x: Int, y: Int): Boolean = roadAt(x + originX, y + originY)

    fun roadTouches(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean =
        roadTouchesGlobal(minX + originX, minY + originY, maxX + originX, maxY + originY)

    private fun roadAt(x: Int, y: Int): Boolean = roadTouchesGlobal(x, y, x, y)

    private fun roadTouchesGlobal(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean {
        val insideGrid = maxX >= 0 && minX < WorldWindow.GRID_TILES && maxY >= 0 && minY < WorldWindow.GRID_TILES
        val crossesColumn = maxX >= ORIGIN_X && minX < ORIGIN_X + ROAD_WIDTH
        val crossesRow = maxY >= ORIGIN_Y && minY < ORIGIN_Y + ROAD_WIDTH
        return insideGrid && (crossesColumn || crossesRow)
    }

    private fun roadVerge(x: Int, y: Int): Boolean = roadTouchesGlobal(x - 1, y - 1, x, y)

    fun biomeAt(x: Int, y: Int): Biome = biomeAtGlobal(x + originX, y + originY)

    private fun tile(grid: Grid, localX: Int, localY: Int): String {
        if (grid.roads[localX][localY]) {
            return "o$ROAD_OVERLAY/0/0,u${grid.underlays[localX][localY]},h${grid.heights[localX][localY]}"
        }
        val liquid = grid.liquids[localX][localY]
        if (liquid != null) {
            return "o${liquid.overlay}/0/0,s1,u${liquid.underlay},h1"
        }
        return "u${grid.underlays[localX][localY]},h${grid.heights[localX][localY]}"
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
        val points = Array(SIZE + 1) { dx ->
            Array(SIZE + 1) { dy ->
                if (roadVerge(baseX + dx, baseY + dy)) null else liquid(biomes[dx][dy], lake[dx][dy], pond[dx][dy], island[dx][dy] > 0, crater[dx][dy])
            }
        }
        val liquids = Array(SIZE) { dx -> Array(SIZE) { dy -> tileLiquid(points, dx, dy) } }
        val heights = Array(SIZE) { dx ->
            IntArray(SIZE) { dy -> height(baseX + dx, baseY + dy, nearest[dx][dy], lake[dx][dy], pond[dx][dy], island[dx][dy], crater[dx][dy]) }
        }
        val underlays = Array(SIZE) { dx ->
            IntArray(SIZE) { dy -> underlay(baseX + dx, baseY + dy, nearest[dx][dy], heights[dx][dy], lake[dx][dy], crater[dx][dy]) }
        }
        val roads = Array(SIZE) { dx -> BooleanArray(SIZE) { dy -> roadAt(baseX + dx, baseY + dy) } }
        val depths = if (!withDepth) null else Array(SIZE) { dx ->
            IntArray(SIZE) { dy ->
                if (liquids[dx][dy] == Liquid.WATER) depth(baseX + dx, baseY + dy, nearest[dx][dy], lake[dx][dy], pond[dx][dy]) else 0
            }
        }
        return Grid(Array(SIZE) { dx -> Array(SIZE) { dy -> biomes[dx][dy] } }, liquids, heights, underlays, roads, depths)
    }

    private fun height(x: Int, y: Int, site: Site, lake: Double, pond: Double, island: Double, crater: Double): Int {
        val biome = site.biome
        val verge = roadVerge(x, y)
        val distances = distances(x, y)
        var terrain = land(x, y)
        val mountain = ramp(fieldOf(distances, Biome.MOUNTAIN), MOUNTAIN_RAMP)
        terrain += mountain * (MOUNTAIN_LIFT + noise(x / MOUNTAIN_PEAK_WAVELENGTH, y / MOUNTAIN_PEAK_WAVELENGTH, MOUNTAIN_SALT) * MOUNTAIN_PEAKS)
        if (mountain > 0) {
            terrain += mountain * cliffs(x, y)
        }
        terrain += ramp(fieldOf(distances, Biome.VOLCANO), VOLCANO_RAMP) * VOLCANO_LIFT
        if (biome == Biome.VOLCANO) {
            terrain += CONE_PEAK * (1 - smooth((hypot(x - site.x, y - site.y) / CONE_RADIUS).coerceIn(0.0, 1.0)))
            terrain = lerp(terrain, WATER_HEIGHT.toDouble(), smooth(((crater + CRATER_BANK) / CRATER_BANK).coerceIn(0.0, 1.0)))
        }
        val basin = if (verge || biome == Biome.OCEAN || biome == Biome.MOUNTAIN) 0.0 else maxOf(
            ((lake - BANK_START) / (lakeLevel(biome) - BANK_START)).coerceIn(0.0, 1.0),
            if (biome == Biome.DESERT) ((pond - POND_BANK_START) / (POND_LEVEL - POND_BANK_START)).coerceIn(0.0, 1.0) else 0.0
        )
        terrain = lerp(terrain, WATER_HEIGHT.toDouble(), basin)
        if (!verge) {
            terrain = lerp(terrain, WATER_HEIGHT.toDouble(), ramp(fieldOf(distances, Biome.OCEAN) + SHORE_BLEND, SHORE_BLEND))
        }
        if (island > 0) {
            terrain = lerp(WATER_HEIGHT.toDouble(), ISLAND_PEAK.toDouble(), smooth((island / ISLAND_SLOPE).coerceIn(0.0, 1.0)))
        }
        return terrain.toInt().coerceIn(WATER_HEIGHT, MAX_HEIGHT)
    }

    private fun cliffs(x: Int, y: Int): Double {
        val ridge = noise(x / CLIFF_WAVELENGTH, y / CLIFF_WAVELENGTH, CLIFF_SALT)
        var steps = 0.0
        for (level in CLIFF_LEVELS) {
            steps += smooth(((ridge - level) / CLIFF_EDGE).coerceIn(0.0, 1.0))
        }
        return steps * CLIFF_STEP * smooth(((trailDistance(x, y) - TRAIL_WIDTH) / TRAIL_CLEARANCE).coerceIn(0.0, 1.0))
    }

    private fun craterField(x: Int, y: Int, site: Site): Double =
        if (site.biome == Biome.VOLCANO) CRATER_RADIUS - hypot(x - site.x, y - site.y) else Double.NEGATIVE_INFINITY

    private fun underlay(x: Int, y: Int, site: Site, height: Int, lake: Double, crater: Double): Int = when (site.biome) {
        Biome.MOUNTAIN -> when {
            trailAt(x, y) -> TRAIL_UNDERLAY
            height >= SNOW_LINE -> WINTER_UNDERLAY
            height < FOOT_LINE -> FOREST_UNDERLAY
            else -> MOUNTAIN_UNDERLAY
        }
        Biome.VOLCANO -> when {
            crater > -EMBER_RING || lake > VOLCANO_LAKE_LEVEL - EMBER_LAKE_MARGIN -> EMBER_UNDERLAY
            noise(x / ASH_WAVELENGTH, y / ASH_WAVELENGTH, ASH_SALT) < OBSIDIAN_SHARE -> OBSIDIAN_UNDERLAY
            else -> ASH_UNDERLAY
        }
        Biome.OCEAN -> if (height <= BEACH_HEIGHT) BEACH_UNDERLAY else site.biome.underlay
        else -> site.biome.underlay
    }

    private fun trailDistance(x: Int, y: Int): Double = abs(noise(x / TRAIL_WAVELENGTH, y / TRAIL_WAVELENGTH, TRAIL_SALT) - 0.5)

    private fun trailAt(x: Int, y: Int): Boolean = trailDistance(x, y) < TRAIL_WIDTH

    private fun trailNear(x: Int, y: Int, size: Int): Boolean {
        for (dx in -1..size step maxOf(1, size / 2 + 1)) {
            for (dy in -1..size step maxOf(1, size / 2 + 1)) {
                if (trailAt(x + dx, y + dy)) return true
            }
        }
        return false
    }

    private fun depth(x: Int, y: Int, site: Site, lake: Double, pond: Double): Int {
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
        val bytes = (body * shelf).toInt()
        return if (bytes < MIN_DEPTH) 0 else minOf(bytes, MAX_DEPTH)
    }

    private fun roadGap(x: Int, y: Int): Double {
        val column = if (x < ORIGIN_X) ORIGIN_X - x else x - (ORIGIN_X + ROAD_WIDTH - 1)
        val row = if (y < ORIGIN_Y) ORIGIN_Y - y else y - (ORIGIN_Y + ROAD_WIDTH - 1)
        return minOf(column, row).toDouble()
    }

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
        biome == Biome.MOUNTAIN -> null
        biome == Biome.VOLCANO && crater > 0 -> Liquid.LAVA
        biome == Biome.DESERT && pond >= POND_LEVEL -> Liquid.WATER
        lake >= lakeLevel(biome) -> if (biome == Biome.WILDERNESS || biome == Biome.VOLCANO) Liquid.LAVA else Liquid.WATER
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
        Biome.VOLCANO -> VOLCANO_LAKE_LEVEL
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
                if (nearLiquid(globalX, globalY, scenery.size)) {
                    continue
                }
                if (roadTouchesGlobal(globalX - 1, globalY - 1, globalX + scenery.size, globalY + scenery.size)) {
                    continue
                }
                if (biome == Biome.MOUNTAIN && trailNear(globalX, globalY, scenery.size)) {
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
                for (fixture in fixturesOf(cellX, cellY) + stationOf(cellX, cellY) + islandsOf(cellX, cellY).flatMap { it.fixtures }) {
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
        Biome.MOUNTAIN -> MOUNTAIN_ORES.map { oreLayout(it) }
        Biome.VOLCANO -> VOLCANO_ORES.map { oreLayout(it) }
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
        if (x0 - AREA_MARGIN < 0 || y0 - AREA_MARGIN < 0) return false
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
                listOfNotNull(lodestone, findFurnace(site, cellX, cellY, listOfNotNull(lodestone)))
            }
            Biome.PLAINS -> findOaks(site)
            Biome.DESERT -> findGemRocks(site)
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
        for (cellX in cellOf(originX) - 1..cellOf(originX + WORLD_SIZE - 1) + 1) {
            for (cellY in cellOf(originY) - 1..cellOf(originY + WORLD_SIZE - 1) + 1) {
                if (site(cellX, cellY).biome != Biome.WILDERNESS) continue
                val lodestone = fixturesOf(cellX, cellY).firstOrNull { it.id == Loc.wilderness_lodestone } ?: continue
                val localX = lodestone.x - originX
                val localY = lodestone.y - originY
                if (localX in 0 until WORLD_SIZE && localY in 0 until WORLD_SIZE && inArea(localX / SIZE, localY / SIZE)) {
                    sites.add(localX to localY)
                }
            }
        }
        return sites
    }

    private fun findOaks(site: Site): List<Fixture> {
        val found = mutableListOf<Fixture>()
        for (number in 0 until PLAINS_OAKS) {
            val hash = hash(site.cellX * FIXTURE_STRIDE + number, site.cellY, OAK_SALT)
            val spot = findSpot(site, hash, OAK_START_RADIUS, OAK_SEARCH_RADIUS, 1) { x, y -> spotClear(site.biome, x, y, OAK_SIZE, OAK_SIZE, found) } ?: continue
            found.add(Fixture(Loc.oaktree, spot.first, spot.second, OAK_SIZE, OAK_SIZE, (hash shr ROTATION_SHIFT and 3).toInt()))
        }
        return found
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
        if (x - SPOT_MARGIN < 0 || y - SPOT_MARGIN < 0) {
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

    private fun cellOf(tile: Int) = Math.floorDiv(tile, BIOME_SPACING)

    private fun islandsOf(cellX: Int, cellY: Int): List<Island> =
        islands.computeIfAbsent(cellKey(cellX, cellY)) { findIslands(cellX, cellY) }

    private fun findIslands(cellX: Int, cellY: Int): List<Island> {
        val site = site(cellX, cellY)
        if (site.biome != Biome.OCEAN) {
            return emptyList()
        }
        val count = ISLAND_MIN + (hash(cellX, cellY, ISLAND_SALT) % (ISLAND_MAX - ISLAND_MIN + 1)).toInt()
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
        return found
    }

    private fun islandFits(site: Site, x: Int, y: Int, radius: Double, others: List<Island>): Boolean {
        if (nearestSite(x, y) !== site) return false
        if (x - radius * ISLAND_REACH - ISLAND_CLEARANCE < 0 || y - radius * ISLAND_REACH - ISLAND_CLEARANCE < 0) return false
        if (fieldOf(distances(x, y), Biome.OCEAN) < radius * ISLAND_REACH + ISLAND_CLEARANCE) return false
        if (roadTouchesGlobal(x - ISLAND_CLEARANCE, y - ISLAND_CLEARANCE, x + ISLAND_CLEARANCE, y + ISLAND_CLEARANCE)) return false
        return others.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < it.radius + radius + ISLAND_GAP }
    }

    private fun islandFixtures(x: Int, y: Int, roll: Long): List<Fixture> {
        val rotation = (roll shr ROTATION_SHIFT and 3).toInt()
        val trees = mutableListOf(Fixture(MAPLE_TREE, x - 3, y - 3, MAPLE_SIZE, MAPLE_SIZE, rotation))
        if (roll shr ISLAND_TREE_SHIFT and 1L == 1L) {
            trees.add(Fixture(MAPLE_TREE, x + 1, y + 1, MAPLE_SIZE, MAPLE_SIZE, rotation))
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
        if (cellX < 0 || cellY < 0) {
            val mirroredX = if (cellX < 0) -cellX - 1 else cellX
            val mirroredY = if (cellY < 0) -cellY - 1 else cellY
            return biomeOf(mirroredX, mirroredY, hash(mirroredX, mirroredY, BIOME_SALT))
        }
        if (cellX == ORIGIN_CELL_X && cellY == ORIGIN_CELL_Y) return Biome.PLAINS
        if ((hash shr WINTER_PICK_SHIFT) % WINTER_SITE_ODDS == 0L) return Biome.WINTER
        val extra = hash(cellX, cellY, EXTRA_BIOME_SALT)
        val onRoadCell = cellX == ORIGIN_CELL_X || cellY == ORIGIN_CELL_Y
        return when {
            !onRoadCell && extra % OCEAN_SITE_ODDS == 0L -> Biome.OCEAN
            (extra shr MOUNTAIN_PICK_SHIFT) % MOUNTAIN_SITE_ODDS == 0L -> Biome.MOUNTAIN
            !onRoadCell && (extra shr VOLCANO_PICK_SHIFT) % VOLCANO_SITE_ODDS == 0L -> Biome.VOLCANO
            else -> LAND_BIOMES[(hash shr BIOME_PICK_SHIFT).toInt().mod(LAND_BIOMES.size)]
        }
    }

    private fun cellKey(cellX: Int, cellY: Int): Long = cellX.toLong() shl CELL_KEY_SHIFT xor (cellY.toLong() and CELL_KEY_MASK)

    private fun fraction(hash: Long, index: Int): Double = (hash shr index * FRACTION_BITS and FRACTION_MASK) / (FRACTION_MASK + 1).toDouble()

    private fun lake(x: Int, y: Int): Double =
        noise(x.toDouble() / LAKE_WAVELENGTH, y.toDouble() / LAKE_WAVELENGTH, LAKE_SALT) * LAKE_MAIN +
            noise(x.toDouble() / LAKE_DETAIL, y.toDouble() / LAKE_DETAIL, LAKE_SALT + 1) * (1 - LAKE_MAIN)

    private fun pond(x: Int, y: Int): Double = noise(x.toDouble() / POND_WAVELENGTH, y.toDouble() / POND_WAVELENGTH, POND_SALT)

    private fun land(x: Int, y: Int): Double {
        var total = BASE_HEIGHT.toDouble()
        for ((wavelength, amplitude) in OCTAVES) {
            total += (noise(x.toDouble() / wavelength, y.toDouble() / wavelength, HEIGHT_SALT) - 0.5) * 2 * amplitude
        }
        return total
    }

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
        const val WORLD_SIZE = 16384
        const val BIOME_SPACING = 340
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
        private val BIOMES = Biome.entries.size
        const val ROAD_OVERLAY = Overlay.overlay_102
        const val ROAD_WIDTH = 2
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
        private val DESERT_ORES = listOf(CLAY_ROCK, IRON_ROCK, TIN_ROCK, COPPER_ROCK)
        private val MOUNTAIN_ORES = listOf(IRON_ROCK, IRON_ROCK, COAL_ROCK, COAL_ROCK)
        private val VOLCANO_ORES = listOf(COAL_ROCK, COAL_ROCK, MITHRIL_ROCK)
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
        private const val OAK_SIZE = 3
        private const val OAK_START_RADIUS = 90
        private const val OAK_SEARCH_RADIUS = 80
        private const val OAK_SALT = 0x4F414BL
        private const val DESERT_GEM_ROCKS = 2
        private const val GEM_START_RADIUS = 90
        private const val GEM_SEARCH_RADIUS = 80
        private const val GEM_SALT = 0x47454DL
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
        private const val TRAIL_SALT = 0x545241L
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
        private const val VOLCANO_LAKE_LEVEL = 0.76
        private const val BANK_START = 0.55
        private const val POND_WAVELENGTH = 40.0
        private const val POND_LEVEL = 0.86
        private const val POND_BANK_START = 0.72
        private const val SHORE = 2
        private const val SHORE_BLEND = 24.0
        private const val FIELD_CAP = 200.0
        private const val MOUNTAIN_RAMP = 80.0
        private const val MOUNTAIN_LIFT = 55.0
        private const val MOUNTAIN_PEAKS = 30.0
        private const val FOOT_LINE = 85
        private const val SNOW_LINE = 155
        private const val CLIFF_WAVELENGTH = 64.0
        private const val CLIFF_EDGE = 0.025
        private const val CLIFF_STEP = 14.0
        private const val CLIFF_SALT = 0x434C4946L
        private val CLIFF_LEVELS = listOf(0.4, 0.55, 0.7)
        private const val TRAIL_CLEARANCE = 0.03
        private const val CONE_PEAK = 60.0
        private const val CONE_RADIUS = 120.0
        private const val CRATER_RADIUS = 26.0
        private const val CRATER_BANK = 10.0
        private const val EMBER_RING = 14.0
        private const val EMBER_LAKE_MARGIN = 0.04
        private const val ASH_WAVELENGTH = 18.0
        private const val ASH_SALT = 0x415348L
        private const val OBSIDIAN_SHARE = 0.3
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
        private const val VOLCANO_RAMP = 60.0
        private const val VOLCANO_LIFT = 20.0
        private const val TRAIL_WAVELENGTH = 70.0
        private const val TRAIL_WIDTH = 0.025
        private val OCTAVES = listOf(160.0 to 34.0, 48.0 to 12.0, 12.0 to 3.0)
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
