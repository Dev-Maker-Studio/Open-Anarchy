package org.darkan.tools.cache.model

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and caches BillboardType configs from cache Index 29 (BILLBOARDS).
 *
 * Matches the client's BillboardTypeList pattern: configs are stored in
 * archive 0 with the billboard ID as the file ID. Results are cached in
 * a ConcurrentHashMap for fast, thread-safe repeated lookups.
 *
 * A billboard with no data in the cache returns a default BillboardType
 * (all default field values), matching the client behavior where
 * BillboardTypeList.list() creates a new BillboardType even if
 * billboardData is null.
 */
object BillboardTypeLoader {

    private val cache = ConcurrentHashMap<Int, BillboardType>()

    /**
     * Get a BillboardType by ID, loading from the game cache if not yet cached.
     *
     * @param gameCache The game cache to load from
     * @param billboardId The billboard config ID
     * @return The decoded BillboardType, or a default instance if not found in cache
     */
    fun get(gameCache: Cache, billboardId: Int): BillboardType {
        return cache.computeIfAbsent(billboardId) {
            val data = gameCache.data(Index.BILLBOARDS, 0, billboardId)
            if (data != null) BillboardType.decode(data) else BillboardType()
        }
    }

    /**
     * Clear the cached billboard types.
     */
    fun clearCache() {
        cache.clear()
    }
}
