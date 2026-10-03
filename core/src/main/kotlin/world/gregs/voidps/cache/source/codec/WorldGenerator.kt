package world.gregs.voidps.cache.source.codec

import org.darkan.core.gameval.gen.Loc
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class WorldGenerator(
    private val seed: Long = DEFAULT_SEED,
    private val originX: Int = 0,
    private val originY: Int = 0,
    /** Side of the square area, in tiles, around the spawn that is really built; 0 builds the whole world. */
    private val areaTiles: Int = 0
) : MapGenerator {

    /** Whether the map square is inside the built area. A dev subsample packs every square outside it as empty void. */
    fun inArea(regionX: Int, regionY: Int): Boolean {
        if (areaTiles <= 0) return true
        val span = (areaTiles + SIZE - 1) / SIZE
        val low = SPAWN_X / SIZE - span / 2
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        return globalX in low until low + span && globalY in (SPAWN_Y / SIZE - span / 2) until (SPAWN_Y / SIZE - span / 2) + span
    }

    /** A terrain square with nothing in it on any level, what a subsample packs outside its area. */
    fun voidTerrain(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
        val void = rows { _ -> row { _ -> VOID_TILE } }
        val text = StringBuilder(TEXT_CAPACITY)
        text.append("{\n  \"region\": \"").append(square.directory).append("\",\n  \"levels\": 4,\n  \"tiles\": [\n")
        text.append(void).append(",\n").append(void).append(",\n").append(void).append(",\n").append(void)
        text.append("\n  ],\n  \"effects\": ").append(EFFECTS).append("\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    /** A locations square with no objects. */
    fun emptyLocations(regionX: Int, regionY: Int): ByteArray =
        MapJson.writeLocs(MapDefinition(regionY + (regionX shl 8), levels = 0), MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY))


    class Scenery(val id: Int, val size: Int, val weight: Int)

    enum class Biome(val underlay: Int, val odds: Long, val scenery: List<Scenery>) {
        PLAINS(PLAINS_UNDERLAY, PLAINS_ODDS, listOf(Scenery(Loc.tree, 2, 1))),
        DESERT(
            DESERT_UNDERLAY, DESERT_ODDS,
            listOf(
                Scenery(Loc.boulder1, 1, 10), Scenery(Loc.boulder2, 1, 10), Scenery(Loc.boulder3, 1, 10), Scenery(Loc.boulder4, 1, 10)
            )
        ),
        FOREST(FOREST_UNDERLAY, FOREST_ODDS, listOf(Scenery(Loc.tree, 2, 3), Scenery(Loc.oaktree, 3, 2), Scenery(Loc.willowtree, 2, 2))),
        WILDERNESS(
            WILDERNESS_UNDERLAY, WILDERNESS_ODDS,
            listOf(Scenery(Loc.deadtree1, 2, 2), Scenery(Loc.deadtree2, 1, 3), Scenery(Loc.deadtree4, 2, 2))
        )
    }

    enum class Liquid(val overlay: Int, val underlay: Int, val smelt: Boolean) {
        WATER(WATER_OVERLAY, WATER_UNDERLAY, false),
        LAVA(LAVA_OVERLAY, WILDERNESS_UNDERLAY, true)
    }

    private class Site(val x: Double, val y: Double, val stretchX: Double, val stretchY: Double, val biome: Biome)

    private class Furnace(val x: Int, val y: Int, val width: Int, val depth: Int, val rotation: Int)

    private class FurnaceSite(val furnace: Furnace?)

    private class OreCluster(val rocks: List<MapObject>, val globalX: List<Int>, val globalY: List<Int>)

    private class Grid(val biomes: Array<Array<Biome>>, val liquids: Array<Array<Liquid?>>, val heights: Array<IntArray>)

    private val sites = ConcurrentHashMap<Long, Site>()

    private val grids = ConcurrentHashMap<Long, Grid>()

    private val furnaces = ConcurrentHashMap<Long, FurnaceSite>()

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

    override fun locations(regionX: Int, regionY: Int): ByteArray {
        val square = MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY)
        val definition = MapDefinition(regionY + (regionX shl 8), levels = 0)
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        val grid = grid(globalX, globalY)
        placeSmelting(definition, grid)
        return MapJson.writeLocs(definition, square)
    }

    /**
     * The trees and rocks of a map square, local to the square. They are not part of [locations]: the world spawns them
     * as objects when the area loads, so the cache carries no tree and the client bakes no shadow for one.
     */
    fun scenery(regionX: Int, regionY: Int): List<MapObject> {
        val definition = MapDefinition(regionY + (regionX shl 8), levels = 0)
        val globalX = originX / SIZE + regionX
        val globalY = originY / SIZE + regionY
        placeScenery(definition, grid(globalX, globalY), globalX, globalY)
        placeFurnaces(definition, globalX, globalY)
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
        val grid = grids.computeIfAbsent(regionX.toLong() shl CELL_KEY_SHIFT xor (regionY.toLong() and CELL_KEY_MASK)) { grid(regionX, regionY) }
        return grid.liquids[Math.floorMod(globalX, SIZE)][Math.floorMod(globalY, SIZE)]
    }

    fun biomeAt(x: Int, y: Int): Biome = biomeAtGlobal(x + originX, y + originY)

    private fun tile(grid: Grid, localX: Int, localY: Int): String {
        val liquid = grid.liquids[localX][localY]
        if (liquid != null) {
            return "o${liquid.overlay}/0/0,s1,u${liquid.underlay},h1"
        }
        return "u${grid.biomes[localX][localY].underlay},h${grid.heights[localX][localY]}"
    }

    private fun grid(regionX: Int, regionY: Int): Grid {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        val biomes = Array(SIZE + 1) { dx -> Array(SIZE + 1) { dy -> biomeAtGlobal(baseX + dx, baseY + dy) } }
        val lake = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> lake(baseX + dx, baseY + dy) } }
        val pond = Array(SIZE + 1) { dx -> DoubleArray(SIZE + 1) { dy -> pond(baseX + dx, baseY + dy) } }
        val points = Array(SIZE + 1) { dx -> Array(SIZE + 1) { dy -> liquid(biomes[dx][dy], lake[dx][dy], pond[dx][dy]) } }
        val liquids = Array(SIZE) { dx -> Array(SIZE) { dy -> tileLiquid(points, dx, dy) } }
        val heights = Array(SIZE) { dx ->
            IntArray(SIZE) { dy ->
                val biome = biomes[dx][dy]
                val basin = maxOf(
                    ((lake[dx][dy] - BANK_START) / (lakeLevel(biome) - BANK_START)).coerceIn(0.0, 1.0),
                    if (biome == Biome.DESERT) ((pond[dx][dy] - POND_BANK_START) / (POND_LEVEL - POND_BANK_START)).coerceIn(0.0, 1.0) else 0.0
                )
                lerp(land(baseX + dx, baseY + dy), WATER_HEIGHT.toDouble(), basin).toInt().coerceIn(WATER_HEIGHT, MAX_HEIGHT)
            }
        }
        return Grid(biomes, liquids, heights)
    }

    private fun liquid(biome: Biome, lake: Double, pond: Double): Liquid? = when {
        biome == Biome.DESERT && pond >= POND_LEVEL -> Liquid.LAVA
        lake >= lakeLevel(biome) -> if (biome == Biome.WILDERNESS) Liquid.LAVA else Liquid.WATER
        else -> null
    }

    private fun tileLiquid(points: Array<Array<Liquid?>>, x: Int, y: Int): Liquid? {
        val liquid = points[x][y] ?: return null
        return liquid.takeIf { points[x + 1][y] == it && points[x][y + 1] == it && points[x + 1][y + 1] == it }
    }

    private fun lakeLevel(biome: Biome) = if (biome == Biome.WILDERNESS) WILDERNESS_LAKE_LEVEL else LAKE_LEVEL

    private fun placeScenery(definition: MapDefinition, grid: Grid, regionX: Int, regionY: Int) {
        for (cellX in 0 until SIZE / CELL) {
            for (cellY in 0 until SIZE / CELL) {
                val hash = hash(regionX * CELLS + cellX, regionY * CELLS + cellY, SCENERY_SALT)
                val biome = grid.biomes[cellX * CELL][cellY * CELL]
                if (hash % biome.odds != 0L) {
                    continue
                }
                val scenery = pick(biome.scenery, hash shr SCENERY_PICK_SHIFT)
                val room = CELL - scenery.size + 1
                val localX = cellX * CELL + (hash / SCENERY_SPREAD % room).toInt()
                val localY = cellY * CELL + (hash / SCENERY_SPREAD / room % room).toInt()
                if (nearLiquid(regionX * SIZE + localX, regionY * SIZE + localY, scenery.size)) {
                    continue
                }
                if (overlapsFurnace(regionX * SIZE + localX, regionY * SIZE + localY, scenery.size)) {
                    continue
                }
                definition.objects.add(MapObject(scenery.id, localX, localY, 0, SCENERY_SHAPE, (hash shr ROTATION_SHIFT and 3).toInt()))
            }
        }
    }

    /** Ore rocks gather in clusters, never scattered: each desert and wilderness biome site has [CLUSTERS_PER_SITE] of them. */
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
        clusters.computeIfAbsent(cellX.toLong() shl CELL_KEY_SHIFT xor (cellY.toLong() and CELL_KEY_MASK)) { findClusters(cellX, cellY) }

    private fun findClusters(cellX: Int, cellY: Int): List<OreCluster> {
        val site = site(cellX, cellY)
        val ores = when (site.biome) {
            Biome.DESERT -> DESERT_ORES
            Biome.WILDERNESS -> WILDERNESS_ORES
            else -> return emptyList()
        }
        val found = mutableListOf<OreCluster>()
        for (number in 0 until CLUSTERS_PER_SITE) {
            val hash = hash(cellX * CLUSTERS_PER_SITE + number, cellY, CLUSTER_SALT)
            val spread = 2 * CLUSTER_START_RADIUS + 1
            val startX = site.x.toInt() - CLUSTER_START_RADIUS + (hash / CLUSTER_SPREAD % spread).toInt()
            val startY = site.y.toInt() - CLUSTER_START_RADIUS + (hash / CLUSTER_SPREAD / spread % spread).toInt()
            val centre = clusterCentre(site.biome, startX, startY, found) ?: continue
            found.add(buildCluster(ores, centre.first, centre.second, hash))
        }
        return found
    }

    private fun clusterCentre(biome: Biome, startX: Int, startY: Int, others: List<OreCluster>): Pair<Int, Int>? {
        for (ring in 0..CLUSTER_SEARCH_RADIUS) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != ring) continue
                    val x = startX + dx
                    val y = startY + dy
                    if (others.none { far -> far.globalX.any { kotlin.math.abs(it - x) < CLUSTER_GAP } && far.globalY.any { kotlin.math.abs(it - y) < CLUSTER_GAP } } &&
                        clusterSpotClear(biome, x, y)
                    ) {
                        return x to y
                    }
                }
            }
        }
        return null
    }

    private fun clusterSpotClear(biome: Biome, x: Int, y: Int): Boolean {
        for (dx in -CLUSTER_RADIUS..CLUSTER_RADIUS) {
            for (dy in -CLUSTER_RADIUS..CLUSTER_RADIUS) {
                if (biomeAtGlobal(x + dx, y + dy) != biome) return false
            }
        }
        return !nearLiquid(x - CLUSTER_RADIUS, y - CLUSTER_RADIUS, 2 * CLUSTER_RADIUS) && !overlapsFurnace(x - CLUSTER_RADIUS, y - CLUSTER_RADIUS, 2 * CLUSTER_RADIUS + 1)
    }

    /** [ROCKS_PER_ORE] rocks of every ore of the biome on distinct tiles of a square around the centre. */
    private fun buildCluster(ores: List<Int>, centreX: Int, centreY: Int, hash: Long): OreCluster {
        val side = 2 * CLUSTER_RADIUS + 1
        val tiles = (0 until side * side).sortedBy { hash(it, ores.size, hash) }
        val rocks = mutableListOf<MapObject>()
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for ((index, ore) in ores.flatMap { ore -> List(ROCKS_PER_ORE) { ore } }.withIndex()) {
            val tile = tiles[index]
            rocks.add(MapObject(ore, 0, 0, 0, SCENERY_SHAPE, (hash shr ROTATION_SHIFT + index and 3).toInt()))
            xs.add(centreX - CLUSTER_RADIUS + tile / side)
            ys.add(centreY - CLUSTER_RADIUS + tile % side)
        }
        return OreCluster(rocks, xs, ys)
    }

    private fun placeFurnaces(definition: MapDefinition, regionX: Int, regionY: Int) {
        val baseX = regionX * SIZE
        val baseY = regionY * SIZE
        for (cellX in cellOf(baseX) - 1..cellOf(baseX + SIZE - 1) + 1) {
            for (cellY in cellOf(baseY) - 1..cellOf(baseY + SIZE - 1) + 1) {
                val furnace = furnaceOf(cellX, cellY) ?: continue
                if (furnace.x in baseX until baseX + SIZE && furnace.y in baseY until baseY + SIZE) {
                    definition.objects.add(MapObject(Loc.built_furnace, furnace.x - baseX, furnace.y - baseY, 0, SCENERY_SHAPE, furnace.rotation))
                }
            }
        }
    }

    private fun overlapsFurnace(x: Int, y: Int, size: Int): Boolean {
        for (cellX in cellOf(x) - 1..cellOf(x + size) + 1) {
            for (cellY in cellOf(y) - 1..cellOf(y + size) + 1) {
                val furnace = furnaceOf(cellX, cellY) ?: continue
                if (x < furnace.x + furnace.width + FURNACE_MARGIN && x + size > furnace.x - FURNACE_MARGIN &&
                    y < furnace.y + furnace.depth + FURNACE_MARGIN && y + size > furnace.y - FURNACE_MARGIN
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun cellOf(tile: Int) = Math.floorDiv(tile, BIOME_SPACING)

    private fun furnaceOf(cellX: Int, cellY: Int): Furnace? =
        furnaces.computeIfAbsent(cellX.toLong() shl CELL_KEY_SHIFT xor (cellY.toLong() and CELL_KEY_MASK)) { FurnaceSite(findFurnace(cellX, cellY)) }.furnace

    private fun findFurnace(cellX: Int, cellY: Int): Furnace? {
        val site = site(cellX, cellY)
        if (site.biome != Biome.WILDERNESS) {
            return null
        }
        val hash = hash(cellX, cellY, FURNACE_SALT)
        val rotation = (hash and 3).toInt()
        val width = if (rotation and 1 == 0) FURNACE_WIDTH else FURNACE_DEPTH
        val depth = if (rotation and 1 == 0) FURNACE_DEPTH else FURNACE_WIDTH
        val spread = 2 * FURNACE_START_RADIUS + 1
        val startX = site.x.toInt() - FURNACE_START_RADIUS + (hash / FURNACE_SPREAD % spread).toInt()
        val startY = site.y.toInt() - FURNACE_START_RADIUS + (hash / FURNACE_SPREAD / spread % spread).toInt()
        for (ring in 0..FURNACE_SEARCH_RADIUS) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != ring) {
                        continue
                    }
                    if (furnaceSpotClear(startX + dx, startY + dy, width, depth)) {
                        return Furnace(startX + dx, startY + dy, width, depth, rotation)
                    }
                }
            }
        }
        return null
    }

    private fun furnaceSpotClear(x: Int, y: Int, width: Int, depth: Int): Boolean {
        for (dx in -FURNACE_MARGIN until width + FURNACE_MARGIN) {
            for (dy in -FURNACE_MARGIN until depth + FURNACE_MARGIN) {
                if (biomeAtGlobal(x + dx, y + dy) != Biome.WILDERNESS) {
                    return false
                }
            }
        }
        return !nearLiquid(x, y, maxOf(width, depth))
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
                val biome = biomeAtGlobal(x + dx, y + dy)
                if (liquid(biome, lake(x + dx, y + dy), pond(x + dx, y + dy)) != null) {
                    return true
                }
            }
        }
        return false
    }

    private fun biomeAtGlobal(x: Int, y: Int): Biome {
        val warpedX = x + (noise(x / WARP_WAVELENGTH, y / WARP_WAVELENGTH, WARP_SALT) - 0.5) * 2 * BIOME_WARP
        val warpedY = y + (noise(x / WARP_WAVELENGTH, y / WARP_WAVELENGTH, WARP_SALT + 1) - 0.5) * 2 * BIOME_WARP
        val cellX = Math.floorDiv(warpedX.toInt(), BIOME_SPACING)
        val cellY = Math.floorDiv(warpedY.toInt(), BIOME_SPACING)
        var nearest = Double.MAX_VALUE
        var biome = Biome.PLAINS
        for (neighbourX in cellX - 1..cellX + 1) {
            for (neighbourY in cellY - 1..cellY + 1) {
                val site = site(neighbourX, neighbourY)
                val dx = (warpedX - site.x) / site.stretchX
                val dy = (warpedY - site.y) / site.stretchY
                val distance = dx * dx + dy * dy
                if (distance < nearest) {
                    nearest = distance
                    biome = site.biome
                }
            }
        }
        return biome
    }

    private fun site(cellX: Int, cellY: Int): Site = sites.computeIfAbsent(cellX.toLong() shl CELL_KEY_SHIFT xor (cellY.toLong() and CELL_KEY_MASK)) {
        val hash = hash(cellX, cellY, BIOME_SALT)
        Site(
            cellX * BIOME_SPACING + BIOME_SPACING / 2.0 + fraction(hash, 0) * 2 * BIOME_JITTER - BIOME_JITTER,
            cellY * BIOME_SPACING + BIOME_SPACING / 2.0 + fraction(hash, 1) * 2 * BIOME_JITTER - BIOME_JITTER,
            MIN_STRETCH + fraction(hash, 2) * (MAX_STRETCH - MIN_STRETCH),
            MIN_STRETCH + fraction(hash, 3) * (MAX_STRETCH - MIN_STRETCH),
            if (cellX == SPAWN_CELL_X && cellY == SPAWN_CELL_Y) Biome.PLAINS else Biome.entries[(hash shr BIOME_PICK_SHIFT).toInt().mod(Biome.entries.size)]
        )
    }

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
        const val BIOME_SPACING = 500
        const val SPAWN_X = 3222
        const val SPAWN_Y = 3218
        private const val SPAWN_CELL_X = SPAWN_X / BIOME_SPACING
        private const val SPAWN_CELL_Y = SPAWN_Y / BIOME_SPACING
        const val MIN_BIOME_WIDTH = 250
        const val PLAINS_UNDERLAY = 161
        const val DESERT_UNDERLAY = 62
        const val FOREST_UNDERLAY = 160
        const val WILDERNESS_UNDERLAY = 149
        const val WATER_OVERLAY = 112
        const val WATER_UNDERLAY = 37
        const val LAVA_OVERLAY = 19
        const val LAVA_SMELT = 73906
        const val LAVA_SMELT_LARGE = 73907
        private const val PLAINS_ODDS = 5L
        private const val DESERT_ODDS = 14L
        private const val FOREST_ODDS = 2L
        private const val WILDERNESS_ODDS = 9L
        private const val CLUSTERS_PER_SITE = 3
        private const val CLUSTER_RADIUS = 2
        private const val CLUSTER_GAP = 40
        private const val CLUSTER_START_RADIUS = 100
        private const val CLUSTER_SEARCH_RADIUS = 60
        private const val CLUSTER_SPREAD = 11L
        private const val CLUSTER_SALT = 0x4F5245L
        private const val ROCKS_PER_ORE = 2
        private val DESERT_ORES = listOf(Loc.loc_11957, Loc.loc_11960, Loc.loc_11954)
        private val WILDERNESS_ORES = listOf(Loc.loc_11933, Loc.loc_11936, Loc.loc_37307, Loc.loc_11930, Loc.loc_11942)
        private const val FURNACE_WIDTH = 2
        private const val FURNACE_DEPTH = 3
        private const val FURNACE_MARGIN = 1
        private const val FURNACE_START_RADIUS = 60
        private const val FURNACE_SEARCH_RADIUS = 80
        private const val FURNACE_SPREAD = 7L
        private const val FURNACE_SALT = 0x46524EL
        private const val SCENERY_SHAPE = 10
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
        private const val BIOME_JITTER = 40.0
        private const val BIOME_WARP = 30.0
        private const val WARP_WAVELENGTH = 90.0
        private const val MIN_STRETCH = 0.8
        private const val MAX_STRETCH = 1.25
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
