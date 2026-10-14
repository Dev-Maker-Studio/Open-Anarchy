package com.rs.game.map

import com.rs.game.content.skills.fishing.FixedFishingSpots
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.util.concurrent.ConcurrentHashMap
import com.rs.game.content.world.generated.StartLodestone

private const val REGION_SIZE = 64
private const val REGION_CACHE_LIMIT = 64

object GeneratedScenery {

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
        FixedFishingSpots.spawn(chunk, generator.fishingSpotsIn(regionX, regionY))
        StartLodestone.spawn(chunk)
    }

    private fun scenery(generator: WorldGenerator, regionX: Int, regionY: Int): List<MapObject> {
        if (regions.size > REGION_CACHE_LIMIT) regions.clear()
        return regions.computeIfAbsent(regionX shl 8 or regionY) { generator.scenery(regionX, regionY) }
    }
}
