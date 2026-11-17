package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
import org.darkan.core.city.City
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
        for (regionX in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES) {
            for (regionY in FIRST_SQUARE until FIRST_SQUARE + AREA_SQUARES) {
                val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
                val saved = File(tree, "$MAPS/${MapCodec.path(square)}")
                val terrain = if (saved.isFile) saved.readBytes() else generator.terrain(regionX, regionY)
                val definition = MapJson.readTerrain(terrain, square)
                val locs = locsOf(generator, cities, regionX, regionY)
                out.write(SQUARE_BY_TILES)
                out.write(regionX)
                out.write(regionY)
                for (localX in 0 until SQUARE_TILES) {
                    for (localY in 0 until SQUARE_TILES) {
                        val tile = definition.getTile(localX, localY, GROUND_LEVEL)
                        val here = locs[localX shl SQUARE_SHIFT or localY]
                        when {
                            here != null -> writeWithLocs(out, tile.underlayId, tile.overlayId, here)
                            tile.overlayId != 0 -> {
                                out.write(OVERLAY_TILE)
                                out.write(tile.overlayId)
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
            }
        }
        return out.toByteArray()
    }

    private fun locsOf(generator: MapGenerator, cities: List<City>, regionX: Int, regionY: Int): Map<Int, List<Pair<Int, Int>>> {
        val result = HashMap<Int, MutableList<Pair<Int, Int>>>()
        fun add(id: Int, shape: Int, rotation: Int, x: Int, y: Int) {
            if (shape == ObjectShape.GROUND_DECORATION.id) return
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
            for (piece in city.buildings.flatMap { it.pieces }) {
                if (piece.x - baseX !in 0 until SQUARE_TILES || piece.y - baseY !in 0 until SQUARE_TILES) continue
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
