package org.darkan.core.map

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import java.util.concurrent.ConcurrentHashMap

data class RegionData(
    val regionId: Int,
    val regionX: Int,
    val regionY: Int,
    val tiles: LongArray,
    val settings: ByteArray,
    val objects: List<MapObject>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegionData) return false
        return regionId == other.regionId
    }

    override fun hashCode(): Int = regionId
}

data class TileInfo(
    val height: Int,
    val overlayId: Int,
    val overlayShape: Int,
    val overlayRotation: Int,
    val underlayId: Int,
    val settings: Int
)

object RegionLoader {
    private class Loaded(val data: RegionData?)

    private val regionCache = ConcurrentHashMap<Int, Loaded>()

    private val tileIndices = IntArray(16384).apply {
        var i = 0
        for (level in 0 until 4) {
            for (localX in 0 until 64) {
                for (localY in 0 until 64) {
                    this[i++] = MapDefinition.index(localX, localY, level)
                }
            }
        }
    }

    fun loadRegion(cache: Cache, regionX: Int, regionY: Int, useCache: Boolean = true): RegionData? {
        val regionId = (regionX shl 8) or regionY

        if (!useCache) return loadRegionInternal(cache, regionX, regionY)
        return regionCache.computeIfAbsent(regionId) { Loaded(loadRegionInternal(cache, regionX, regionY)) }.data
    }

    fun loadRegion(cache: Cache, regionId: Int, useCache: Boolean = true): RegionData? {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff
        return loadRegion(cache, regionX, regionY, useCache)
    }

    private fun loadRegionInternal(cache: Cache, regionX: Int, regionY: Int): RegionData? {
        val regionId = (regionX shl 8) or regionY

        val terrainArchive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (terrainArchive == -1) return null

        val terrainData = cache.data(Index.MAPS, terrainArchive) ?: return null

        val tiles = LongArray(64 * 64 * 4)
        val settings = ByteArray(64 * 64 * 4)
        decodeTerrain(terrainData, tiles, settings)

        val objectArchive = cache.archiveId(Index.MAPS, "l${regionX}_$regionY")
        val objects = if (objectArchive != -1) {
            val xtea = MapXteas.getKeys(regionId)
            val objectData = cache.data(Index.MAPS, objectArchive, 0, xtea)
            if (objectData != null && objectData.isNotEmpty()) {
                decodeObjects(objectData, settings, regionX, regionY, xtea != null)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        return RegionData(regionId, regionX, regionY, tiles, settings, objects)
    }

    private fun decodeTerrain(data: ByteArray, tiles: LongArray, settings: ByteArray) {
        var position = 0
        val size = data.size

        for (index in tileIndices) {
            var height = 0
            var overlayId = 0
            var overlayShape = 0
            var overlayRotation = 0
            var underlayId = 0
            var setting = 0

            while (position < size) {
                val opcode = data[position++].toInt() and 0xff

                when {
                    opcode == 0 -> break
                    opcode == 1 -> {
                        height = data[position++].toInt() and 0xff
                        break
                    }
                    opcode <= 49 -> {
                        overlayId = data[position++].toInt() and 0xff
                        overlayShape = (opcode - 2) / 4
                        overlayRotation = (opcode - 2) and 3
                    }
                    opcode <= 81 -> {
                        setting = opcode - 49
                    }
                    else -> {
                        underlayId = opcode - 81
                    }
                }
            }

            tiles[index] = packTile(height, overlayId, overlayShape, overlayRotation, underlayId, setting)
            settings[index] = setting.toByte()
        }
    }

    private fun packTile(
        height: Int,
        overlayId: Int,
        overlayShape: Int,
        overlayRotation: Int,
        underlayId: Int,
        settings: Int
    ): Long {
        return height.toLong() or
               (overlayId.toLong() shl 16) or
               (overlayShape.toLong() shl 24) or
               (overlayRotation.toLong() shl 32) or
               (settings.toLong() shl 40) or
               (underlayId.toLong() shl 48)
    }

    private fun decodeObjects(
        data: ByteArray,
        settings: ByteArray,
        regionTileX: Int,
        regionTileY: Int,
        encrypted: Boolean
    ): List<MapObject> {
        val objects = mutableListOf<MapObject>()

        val decoder = object : MapObjectDecoder() {
            override fun add(
                objectId: Int,
                localX: Int,
                localY: Int,
                level: Int,
                shape: Int,
                rotation: Int,
                regionTileX: Int,
                regionTileY: Int
            ) {
                val worldX = regionTileX * 64 + localX
                val worldY = regionTileY * 64 + localY

                objects.add(MapObject(objectId, worldX, worldY, level, shape, rotation))
            }
        }

        try {
            decoder.decode(data, settings, regionTileX, regionTileY)
        } catch (e: Exception) {
            System.err.println(
                "[RegionLoader] region ($regionTileX, $regionTileY): object decode failed after " +
                    "${objects.size} object(s) - " +
                    if (encrypted) "encrypted, XTEA keys may be wrong: ${e}"
                    else "UNENCRYPTED data, so this is a decoder fault, not a key problem: ${e}"
            )
            return emptyList()
        }

        return objects
    }

    fun getTileInfo(tiles: LongArray, localX: Int, localY: Int, level: Int): TileInfo {
        val packed = tiles[MapDefinition.index(localX, localY, level)]
        return TileInfo(
            height = (packed and 0xff).toInt(),
            overlayId = ((packed shr 16) and 0xff).toInt(),
            overlayShape = ((packed shr 24) and 0xff).toInt(),
            overlayRotation = ((packed shr 32) and 0xff).toInt(),
            underlayId = ((packed shr 48) and 0xff).toInt(),
            settings = ((packed shr 40) and 0xff).toInt()
        )
    }

    fun getTileHeight(tiles: LongArray, localX: Int, localY: Int, level: Int): Int {
        return (tiles[MapDefinition.index(localX, localY, level)] and 0xff).toInt()
    }

    fun regionExists(cache: Cache, regionX: Int, regionY: Int): Boolean {
        return cache.archiveId(Index.MAPS, "m${regionX}_$regionY") != -1
    }

    fun getAvailableRegions(cache: Cache): List<Pair<Int, Int>> {
        val regions = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                if (regionExists(cache, x, y)) {
                    regions.add(Pair(x, y))
                }
            }
        }
        return regions
    }

    fun clearCache() {
        regionCache.clear()
    }

    fun getCacheSize(): Int = regionCache.size
}
