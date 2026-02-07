package org.darkan.tools.cache.model

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Loads and caches BillboardType configs from cache Index 29 (BILLBOARDS).
 *
 * Matches the client's BillboardTypeList pattern: configs are stored in
 * archive 0 with the billboard ID as the file ID. Results are cached in
 * a HashMap for fast repeated lookups.
 *
 * A billboard with no data in the cache returns a default BillboardType
 * (all default field values), matching the client behavior where
 * BillboardTypeList.list() creates a new BillboardType even if
 * billboardData is null.
 */
object BillboardTypeLoader {

    private val cache = HashMap<Int, BillboardType>()

    /**
     * Get a BillboardType by ID, loading from the game cache if not yet cached.
     *
     * @param gameCache The game cache to load from
     * @param billboardId The billboard config ID
     * @return The decoded BillboardType, or a default instance if not found in cache
     */
    fun get(gameCache: Cache, billboardId: Int): BillboardType {
        cache[billboardId]?.let { return it }

        val data = gameCache.data(Index.BILLBOARDS, 0, billboardId)
        val type = if (data != null) {
            BillboardType.decode(data)
        } else {
            BillboardType()
        }
        cache[billboardId] = type
        return type
    }

    /**
     * Clear the cached billboard types.
     */
    fun clearCache() {
        cache.clear()
    }
}
