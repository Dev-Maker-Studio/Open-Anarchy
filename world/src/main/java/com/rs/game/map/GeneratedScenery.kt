package com.rs.game.map

import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.util.concurrent.ConcurrentHashMap

private const val REGION_SIZE = 64
private const val REGION_CACHE_LIMIT = 64

/**
 * The trees and rocks of a generated world are objects the server spawns when a chunk loads, not part of the map in the cache,
 * so they behave like any other spawned object: felling one removes it, and no client bakes a shadow into the terrain for it.
 */
object GeneratedScenery {

    /** A region's scenery, kept while its 64 chunks load one after another; the generator builds a whole region at a time. */
    private val regions = ConcurrentHashMap<Int, List<MapObject>>()

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps) return
        val generator = MapCodec.generator as? WorldGenerator ?: return
        val regionX = chunk.regionX
        val regionY = chunk.regionY
        if (!generator.inArea(regionX, regionY)) return
        val baseX = regionX * REGION_SIZE
        val baseY = regionY * REGION_SIZE
        for (scenery in scenery(generator, regionX, regionY)) {
            val x = baseX + scenery.x
            val y = baseY + scenery.y
            if (scenery.level != chunk.plane || x shr 3 != chunk.x || y shr 3 != chunk.y) continue
            val shape = ObjectShape.forId(scenery.shape) ?: continue
            chunk.addGeneratedObject(GameObject(scenery.id, shape, scenery.rotation, x, y, scenery.level))
        }
    }

    private fun scenery(generator: WorldGenerator, regionX: Int, regionY: Int): List<MapObject> {
        if (regions.size > REGION_CACHE_LIMIT) regions.clear()
        return regions.computeIfAbsent(regionX shl 8 or regionY) { generator.scenery(regionX, regionY) }
    }
}
