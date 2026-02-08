package org.darkan.tools.util

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.MemoryCache

/**
 * Utility functions for cache access validation in parallel code paths.
 */
object CacheUtils {
    /**
     * Asserts that the global cache instance is a [MemoryCache], which is required
     * for parallel loading. [world.gregs.voidps.cache.FileCache] performs disk I/O
     * with shared file handles that are not safe for concurrent access.
     *
     * Call this at parallel code entry points before dispatching work to
     * multiple threads.
     *
     * @throws IllegalStateException if the cache is not a MemoryCache
     */
    fun requireMemoryCache() {
        require(Cache.get() is MemoryCache) {
            "Parallel loading requires MEM_CACHE=true (MemoryCache). " +
                "FileCache is not safe for concurrent access."
        }
    }
}
