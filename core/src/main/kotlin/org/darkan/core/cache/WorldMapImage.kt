package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
import org.darkan.core.city.City
import org.darkan.core.city.DugRoads
import org.darkan.core.city.Service
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.source.codec.WorldGenerator
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapGenerator
import world.gregs.voidps.cache.source.codec.MapJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WorldMapImage {

    private const val IMAGE = "worldmapdata/main/4.dat"
    private const val SQUARE_CACHE = "./data/worldmap-squares-v1"
    private const val VISITED_FILE = "./data/worldmap-visited.txt"
    private const val REBUILD_VARIABLE = "WORLD_MAP_REBUILD"
    private const val SAVED_SUFFIX = "saved.bin"
    private const val GENERATED_SUFFIX = "gen.bin"
    private const val MAPS = "maps"
    private const val SQUARE_TILES = MapDefinition.SIZE
    private const val AREA_SQUARES = WorldWindow.MAP_TILES / SQUARE_TILES
    private const val FIRST_SQUARE = WorldWindow.MAP_ORIGIN / SQUARE_TILES
    private const val GROUND_LEVEL = 0
    private const val EMPTY_TABLE_COUNT = 0
    private const val SQUARE_BY_TILES = 0
    private const val SQUARE_SHIFT = 6
    private const val FLOOR_EXPLICIT = 63
    private const val FLOOR_SKIPPED = 62
    private const val FLOOR_SHIFT = 2
    private const val OVERLAY_FLAG = 2

    private const val SQUARE_EXTENDED = 1
    private const val HAS_OVERLAY = 0x8
    private const val HAS_LOCS = 0x10
    private const val SHAPE_SHIFT = 2
    private const val BIG_SMART_LIMIT = 32767
    private const val BIG_SMART_FLAG = 0x80000000.toInt()
    private const val MAX_LOCS = 255
    private const val SHAPE_ROTATION_NONE = 0

    private val mapIcons = Service.entries.flatMapTo(HashSet()) { it.icons }
    private val UNDERLAY_TILE = FLOOR_EXPLICIT shl FLOOR_SHIFT
    private val OVERLAY_TILE = (FLOOR_EXPLICIT shl FLOOR_SHIFT) or OVERLAY_FLAG
    private val SKIPPED_TILE = FLOOR_SKIPPED shl FLOOR_SHIFT

    fun write(tree: File, generator: MapGenerator, cities: List<City>) {
        val data = build(tree, generator, cities)
        val file = File(tree, IMAGE)
        if (file.isFile && file.readBytes().contentEquals(data)) {
            return
        }
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(data)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logInfo("Wrote the world map image for ${AREA_SQUARES * SQUARE_TILES} x ${AREA_SQUARES * SQUARE_TILES} tiles into ${file.path}")
    }

    private fun build(tree: File, generator: MapGenerator, cities: List<City>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(EMPTY_TABLE_COUNT)
        out.write(EMPTY_TABLE_COUNT)
        val visitedNow = visited
        val full = System.getenv(REBUILD_VARIABLE) == "true"
        var rebuilt = 0
        for (regionX in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES) {
            for (regionY in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES) {
                val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
                val saved = File(tree, "$MAPS/${MapCodec.path(square)}")
                val cache = File(SQUARE_CACHE, "$regionX-$regionY.${if (saved.isFile) SAVED_SUFFIX else GENERATED_SUFFIX}")
                val baseX = regionX * SQUARE_TILES
                val baseY = regionY * SQUARE_TILES
                val reusable = !full
                    && cache.isFile
                    && squareKey(regionX, regionY) !in visitedNow
                    && (!saved.isFile || cache.lastModified() >= saved.lastModified())
                    && cities.none { it.touches(baseX, baseY, baseX + SQUARE_TILES - 1, baseY + SQUARE_TILES - 1) }
                if (reusable) {
                    out.write(cache.readBytes())
                    continue
                }
                val data = squareBytes(saved, square, generator, cities, regionX, regionY)
                cache.parentFile.mkdirs()
                File(SQUARE_CACHE).listFiles { file -> file.name.startsWith("$regionX-$regionY.") && file != cache }?.forEach { it.delete() }
                cache.writeBytes(data)
                out.write(data)
                rebuilt++
            }
        }
        logInfo("World map image: rebuilt $rebuilt of ${AREA_SQUARES * AREA_SQUARES} squares, the rest came from the square cache")
        return out.toByteArray()
    }

    fun markVisited(squareX: Int, squareY: Int) {
        val key = squareKey(squareX, squareY)
        if (squareX !in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES || squareY !in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES) return
        if (!visited.add(key)) return
        val file = File(VISITED_FILE)
        file.parentFile?.mkdirs()
        file.appendText("$squareX $squareY\n")
    }

    private val visited: MutableSet<Long> by lazy {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        File(VISITED_FILE).takeIf { it.isFile }?.forEachLine { line ->
            val parts = line.split(' ')
            if (parts.size == 2) set += squareKey(parts[0].toInt(), parts[1].toInt())
        }
        set
    }

    private fun squareKey(squareX: Int, squareY: Int): Long = squareX.toLong() shl 32 or squareY.toLong()

    private fun squareBytes(saved: File, square: MapSquare.Square, generator: MapGenerator, cities: List<City>, regionX: Int, regionY: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val terrain = if (saved.isFile) saved.readBytes() else generator.terrain(regionX, regionY)
        val definition = MapJson.readTerrain(terrain, square)
        val locs = locsOf(generator, cities, regionX, regionY)
        val baseX = regionX * SQUARE_TILES
        val baseY = regionY * SQUARE_TILES
        val dug = DugRoads.within(baseX, baseY, baseX + SQUARE_TILES - 1, baseY + SQUARE_TILES - 1).mapTo(HashSet()) { (x, y) -> x - baseX shl SQUARE_SHIFT or y - baseY }
        out.write(SQUARE_BY_TILES)
        out.write(regionX)
        out.write(regionY)
        for (localX in 0 until SQUARE_TILES) {
            for (localY in 0 until SQUARE_TILES) {
                val tile = definition.getTile(localX, localY, GROUND_LEVEL)
                val key = localX shl SQUARE_SHIFT or localY
                val here = locs[key]
                val overlay = if (key in dug) WorldGenerator.ROAD_OVERLAY else tile.overlayId
                when {
                    here != null -> writeWithLocs(out, tile.underlayId, overlay, here)
                    overlay != 0 -> {
                        out.write(OVERLAY_TILE)
                        out.write(overlay)
                        out.write(tile.underlayId)
                    }
                    tile.underlayId != 0 -> {
                        out.write(UNDERLAY_TILE)
                        out.write(tile.underlayId)
                    }
                    else -> out.write(SKIPPED_TILE)
                }
            }
        }
        return out.toByteArray()
    }

    private fun locsOf(generator: MapGenerator, cities: List<City>, regionX: Int, regionY: Int): Map<Int, List<Pair<Int, Int>>> {
        val result = HashMap<Int, MutableList<Pair<Int, Int>>>()
        fun add(id: Int, shape: Int, rotation: Int, x: Int, y: Int) {
            if (shape == ObjectShape.GROUND_DECORATION.id && id !in mapIcons) return
            val list = result.getOrPut(x shl SQUARE_SHIFT or y) { ArrayList() }
            if (list.size < MAX_LOCS) list += id to (shape shl SHAPE_SHIFT or rotation)
        }
        if (generator is WorldGenerator && generator.inArea(regionX, regionY)) {
            for (scenery in generator.scenery(regionX, regionY)) if (scenery.level == GROUND_LEVEL) add(scenery.id, scenery.shape, scenery.rotation, scenery.x, scenery.y)
        }
        val baseX = regionX * SQUARE_TILES
        val baseY = regionY * SQUARE_TILES
        for (city in cities) {
            if (!city.touches(baseX, baseY, baseX + SQUARE_TILES - 1, baseY + SQUARE_TILES - 1)) continue
            for (piece in city.pieces()) {
                if (piece.level != GROUND_LEVEL || piece.x - baseX !in 0 until SQUARE_TILES || piece.y - baseY !in 0 until SQUARE_TILES) continue
                add(piece.loc, piece.shape, piece.rotation, piece.x - baseX, piece.y - baseY)
            }
        }
        return result
    }

    private fun writeWithLocs(out: ByteArrayOutputStream, underlay: Int, overlay: Int, locs: List<Pair<Int, Int>>) {
        out.write(SQUARE_EXTENDED or (if (overlay != 0) HAS_OVERLAY else 0) or HAS_LOCS)
        out.write(underlay)
        if (overlay != 0) {
            out.write(overlay)
            out.write(SHAPE_ROTATION_NONE)
        }
        out.write(locs.size)
        for ((id, shapeAndRotation) in locs) {
            if (id < BIG_SMART_LIMIT) {
                out.write(id shr 8)
                out.write(id)
            } else {
                val flagged = id or BIG_SMART_FLAG
                out.write(flagged ushr 24)
                out.write(flagged ushr 16)
                out.write(flagged ushr 8)
                out.write(flagged)
            }
            out.write(shapeAndRotation)
        }
    }
}
