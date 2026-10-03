package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
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
    private const val AREA_SQUARES = 64
    private const val SQUARE_TILES = MapDefinition.SIZE
    private const val GROUND_LEVEL = 0
    private const val EMPTY_TABLE_COUNT = 0
    private const val SQUARE_BY_TILES = 0
    private const val FLOOR_EXPLICIT = 63
    private const val FLOOR_SKIPPED = 62
    private const val FLOOR_SHIFT = 2
    private const val OVERLAY_FLAG = 2

    private val UNDERLAY_TILE = FLOOR_EXPLICIT shl FLOOR_SHIFT
    private val OVERLAY_TILE = (FLOOR_EXPLICIT shl FLOOR_SHIFT) or OVERLAY_FLAG
    private val SKIPPED_TILE = FLOOR_SKIPPED shl FLOOR_SHIFT

    fun write(tree: File, generator: MapGenerator) {
        val data = build(tree, generator)
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

    private fun build(tree: File, generator: MapGenerator): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(EMPTY_TABLE_COUNT)
        out.write(EMPTY_TABLE_COUNT)
        for (regionX in 0 until AREA_SQUARES) {
            for (regionY in 0 until AREA_SQUARES) {
                val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
                val saved = File(tree, "$MAPS/${MapCodec.path(square)}")
                val terrain = if (saved.isFile) saved.readBytes() else generator.terrain(regionX, regionY)
                val definition = MapJson.readTerrain(terrain, square)
                out.write(SQUARE_BY_TILES)
                out.write(regionX)
                out.write(regionY)
                for (localX in 0 until SQUARE_TILES) {
                    for (localY in 0 until SQUARE_TILES) {
                        val tile = definition.getTile(localX, localY, GROUND_LEVEL)
                        when {
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
}
