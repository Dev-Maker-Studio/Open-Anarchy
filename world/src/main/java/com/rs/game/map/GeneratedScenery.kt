package com.rs.game.map

import com.rs.game.content.skills.fishing.FixedFishingSpots
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import com.rs.game.content.world.generated.StartLodestone
import com.rs.game.content.world.generated.Cities

private const val REGION_SIZE = 64
private const val REGION_CACHE_LIMIT = 64
private const val FROZEN_DIRECTORY = "./data/generated-scenery"
private const val FROZEN_EXTENSION = "bin"

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
            if (Cities.covers(x, y)) continue
            val shape = ObjectShape.forId(scenery.shape) ?: continue
            chunk.addGeneratedObject(GameObject(scenery.id, shape, scenery.rotation, x, y, scenery.level))
        }
        FixedFishingSpots.spawn(chunk, generator.fishingSpotsIn(regionX, regionY))
        StartLodestone.spawn(chunk)
        Cities.spawn(chunk)
    }

    private fun scenery(generator: WorldGenerator, regionX: Int, regionY: Int): List<MapObject> {
        if (regions.size > REGION_CACHE_LIMIT) regions.clear()
        return regions.computeIfAbsent(regionX shl 8 or regionY) { frozen(generator, regionX, regionY) }
    }

    private fun frozen(generator: WorldGenerator, regionX: Int, regionY: Int): List<MapObject> {
        val file = File("$FROZEN_DIRECTORY/sector-${EnvVars.worldId}", "${regionX}_$regionY.$FROZEN_EXTENSION")
        read(file)?.let { return it }
        val generated = generator.scenery(regionX, regionY)
        write(file, generated)
        return generated
    }

    private fun read(file: File): List<MapObject>? = runCatching {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input -> List(input.readInt()) { MapObject(input.readLong()) } }
    }.getOrNull()

    private fun write(file: File, objects: List<MapObject>) {
        runCatching {
            file.parentFile.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.${Thread.currentThread().threadId()}.tmp")
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(objects.size)
                objects.forEach { output.writeLong(it.packed) }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
