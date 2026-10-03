package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapGenerator
import org.darkan.core.map.GeneratedWorld
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object MapMaterializer {

    private val saved = ConcurrentHashMap.newKeySet<Int>()

    private val generator: MapGenerator get() = MapCodec.generator ?: GeneratedWorld.generator

    @JvmStatic
    fun explored(regionX: Int, regionY: Int) {
        val tree = CacheSource.tree
        if (!EnvVars.generateMaps || EnvVars.windowed || tree == null || regionX !in REGIONS || regionY !in REGIONS) {
            return
        }
        if (!saved.add(regionX shl REGION_BITS or regionY)) {
            return
        }
        for (shape in GENERATED) {
            val square = MapSquare.Square(shape, regionX, regionY)
            val file = File(tree, "$MAPS/${MapCodec.path(square)}")
            if (file.exists()) {
                continue
            }
            file.parentFile.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(MapCodec.generate(generator, square) ?: continue)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        logInfo("Saved generated map square ${regionX}_$regionY into ${tree.path}")
    }

    private val GENERATED = listOf(MapSquare.Shape.TERRAIN, MapSquare.Shape.LOCATIONS)
    private const val MAPS = "maps"
    private const val REGION_BITS = 8
    private val REGIONS = 0..0xff
}
