package org.darkan.core.map

import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.WorldObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinitionFull
import world.gregs.voidps.type.Tile

/**
 * Loads map data from the cache into the collision map and chunk system.
 *
 * Startup flow:
 * 1. [MapXteas.load] XTEA keys
 * 2. [loadAllMaps] iterates available regions, loading terrain collision + objects
 */
object MapLoader {

    /** Tile settings bit: tile is blocked (floor). */
    private const val BLOCKED_TILE = 0x1

    /** Tile settings bit: bridge / linked plane. */
    private const val BRIDGE_TILE = 0x2

    /** Tile settings bit: under a roof. */
    private const val ROOF_TILE = 0x4

    /**
     * Load all available map regions from the cache.
     * Populates [WorldCollision] flags and [ChunkManager] chunks with base objects.
     *
     * @param cache The game cache
     * @return The number of regions loaded
     */
    fun loadAllMaps(cache: Cache): Int {
        val objectDefs = Cache.objects
        val regions = RegionLoader.getAvailableRegions(cache)
        var regionsLoaded = 0
        var objectsLoaded = 0

        for ((regionX, regionY) in regions) {
            val regionId = (regionX shl 8) or regionY
            val data = RegionLoader.loadRegion(cache, regionX, regionY) ?: continue

            // Step 1: Load terrain collision flags
            loadTerrainCollision(data, regionX, regionY)

            // Step 2: Load objects into chunks and clip them
            objectsLoaded += loadObjects(data, regionX, regionY, objectDefs)

            ChunkManager.markRegionLoaded(regionId)
            regionsLoaded++
        }

        logInfo("Loaded $regionsLoaded regions, $objectsLoaded objects, ${ChunkManager.chunkCount()} chunks")
        return regionsLoaded
    }

    /**
     * Load a single region by ID (for lazy loading).
     */
    fun loadRegion(cache: Cache, regionId: Int) {
        if (ChunkManager.isRegionLoaded(regionId)) return
        val regionX = regionId shr 8
        val regionY = regionId and 0xFF
        val data = RegionLoader.loadRegion(cache, regionX, regionY) ?: return
        val objectDefs = Cache.objects
        loadTerrainCollision(data, regionX, regionY)
        loadObjects(data, regionX, regionY, objectDefs)
        ChunkManager.markRegionLoaded(regionId)
    }

    /**
     * Populate collision flags from terrain tile settings.
     *
     * Tile settings from the cache:
     * - Bit 0 (1): Blocked tile → add PFBW_FLOOR collision
     * - Bit 1 (2): Bridge tile → level is shifted down by 1
     * - Bit 2 (4): Under roof → add UNDER_ROOF flag
     */
    private fun loadTerrainCollision(data: RegionData, regionX: Int, regionY: Int) {
        val baseX = regionX shl 6
        val baseY = regionY shl 6

        for (level in 0 until 4) {
            for (localX in 0 until 64) {
                for (localY in 0 until 64) {
                    val settings = data.settings[MapDefinition.index(localX, localY, level)].toInt() and 0xFF

                    if (settings and BLOCKED_TILE != 0) {
                        // Check if the tile above is a bridge — if so, clip on the level below
                        var clipLevel = level
                        if (level < 3) {
                            val aboveSettings = data.settings[MapDefinition.index(localX, localY, level + 1)].toInt() and 0xFF
                            if (aboveSettings and BRIDGE_TILE != 0) {
                                clipLevel = level - 1
                            }
                        }
                        if (clipLevel >= 0) {
                            WorldCollision.addBlockedTile(Tile(baseX + localX, baseY + localY, clipLevel))
                        }
                    }

                    if (settings and ROOF_TILE != 0) {
                        WorldCollision.addFlag(baseX + localX, baseY + localY, level, ClipFlag.UNDER_ROOF.flag)
                    }
                }
            }
        }
    }

    /**
     * Load objects from region data into chunks and clip them into collision.
     *
     * @return Number of objects loaded
     */
    private fun loadObjects(
        data: RegionData,
        regionX: Int,
        regionY: Int,
        objectDefs: Array<ObjectDefinitionFull>
    ): Int {
        var count = 0
        val baseX = regionX shl 6
        val baseY = regionY shl 6

        for (mapObj in data.objects) {
            val id = mapObj.id
            if (id < 0 || id >= objectDefs.size) continue

            val x = mapObj.x
            val y = mapObj.y
            var level = mapObj.level
            val shape = ObjectShape.forId(mapObj.shape) ?: continue
            val rotation = mapObj.rotation

            // Bridge adjustment: if the tile above has BRIDGE_TILE, clip at level-1
            val localX = x - baseX
            val localY = y - baseY
            if (localX in 0 until 64 && localY in 0 until 64 && level < 3) {
                val aboveSettings = data.settings[MapDefinition.index(localX, localY, level + 1)].toInt() and 0xFF
                if (aboveSettings and BRIDGE_TILE != 0) {
                    level -= 1
                }
            }
            if (level < 0) continue

            val tile = Tile(x, y, level)
            val obj = WorldObject(id, shape, rotation, tile)

            // Add to chunk
            val chunkId = Chunk.chunkId(x, y, level)
            val chunk = ChunkManager.getChunk(chunkId)
            chunk.addBaseObject(obj)

            // Clip into collision
            val def = objectDefs[id]
            WorldCollision.clipObject(
                tile = tile,
                id = id,
                shape = shape,
                rotation = rotation,
                clipType = def.solid,
                blocksProjectiles = (def.block and ObjectDefinitionFull.PROJECTILE) != 0,
                ignoresPathfinder = (def.block and ObjectDefinitionFull.ROUTE) == 0,
                sizeX = def.sizeX,
                sizeY = def.sizeY
            )
            count++
        }
        return count
    }
}
