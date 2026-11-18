package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
import org.darkan.core.city.City
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapGenerator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object CityTerrain {
    private const val MAPS = "maps"
    private const val SQUARE_TILES = MapDefinition.SIZE
    private const val BLEND = 13

    fun write(tree: File, generator: MapGenerator, cities: List<City>, manholes: List<Pair<Int, Int>> = emptyList()) {
        var written = 0
        for (city in cities) {
            val reach = city.halfSize + BLEND
            for (regionX in (city.centerX - reach) / SQUARE_TILES..(city.centerX + reach) / SQUARE_TILES) {
                for (regionY in (city.centerY - reach) / SQUARE_TILES..(city.centerY + reach) / SQUARE_TILES) {
                    val square = MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY)
                    val file = File(tree, "$MAPS/${MapCodec.path(square)}")
                    val terrain = ManholeGround.apply(generator.terrain(regionX, regionY), regionX, regionY, manholes)
                    if (file.isFile && file.readBytes().contentEquals(terrain)) continue
                    file.parentFile.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeBytes(terrain)
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    written++
                }
            }
        }
        if (written > 0) logInfo("Wrote $written city terrain squares into ${tree.path}/$MAPS")
    }
}
