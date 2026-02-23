package org.darkan.core.map

import org.darkan.core.model.WorldObject
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Central manager for all chunks in the world.
 * Provides O(1) access to chunks by ID, tracks active chunks for tick processing.
 */
object ChunkManager {
    private val chunks = ConcurrentHashMap<Int, Chunk>()

    /** Chunks with pending updates this tick. */
    private val activeChunks = ConcurrentHashMap.newKeySet<Int>()

    /** Pending temporary object spawns/removals with tick timers. */
    private val tempObjects = ConcurrentLinkedQueue<TempObjectEntry>()

    private data class TempObjectEntry(
        val spawned: WorldObject,
        val original: WorldObject?,
        val chunkId: Int,
        var ticksRemaining: Int,
    )

    /** Regions that have been loaded. */
    private val loadedRegions = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Get or create a chunk by ID.
     */
    fun getChunk(chunkId: Int): Chunk {
        return chunks.getOrPut(chunkId) { Chunk(chunkId) }
    }

    /**
     * Get a chunk by world coordinates and level.
     */
    fun getChunk(x: Int, y: Int, level: Int): Chunk {
        return getChunk(Chunk.chunkId(x, y, level))
    }

    /**
     * Get chunk if it exists, without creating.
     */
    fun getChunkIfLoaded(chunkId: Int): Chunk? = chunks[chunkId]

    /**
     * Mark a chunk as having pending updates.
     */
    fun markActive(chunkId: Int) {
        activeChunks.add(chunkId)
    }

    /**
     * Spawn a temporary object that reverts after [ticks] ticks.
     * The original object at the same tile+slot is saved and restored when the timer expires.
     */
    fun spawnObjectTemporary(obj: WorldObject, ticks: Int) {
        val cId = Chunk.chunkId(obj.tile.x, obj.tile.y, obj.tile.level)
        val chunk = getChunk(cId)
        val original = chunk.getObject(obj.tile, obj.slot)?.let { WorldObject(it) }
        chunk.spawnObject(obj)
        tempObjects.add(TempObjectEntry(obj, original, cId, ticks))
    }

    /**
     * Temporarily remove an object for [ticks] ticks, then restore it.
     */
    fun removeObjectTemporary(obj: WorldObject, ticks: Int) {
        val cId = Chunk.chunkId(obj.tile.x, obj.tile.y, obj.tile.level)
        val chunk = getChunk(cId)
        chunk.removeObject(obj)
        tempObjects.add(TempObjectEntry(obj, obj, cId, ticks))
    }

    /**
     * Process all active chunks this tick: decrement temp object timers, revert expired.
     */
    fun processChunks() {
        val iterator = tempObjects.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.ticksRemaining--
            if (entry.ticksRemaining <= 0) {
                iterator.remove()
                val chunk = getChunkIfLoaded(entry.chunkId) ?: continue
                // Remove the temp object
                chunk.spawnedObjects.removeAll { it.tile == entry.spawned.tile && it.slot == entry.spawned.slot }
                // Restore original if there was one
                if (entry.original != null) {
                    chunk.removedBaseObjects.removeAll { it.tile == entry.original.tile && it.slot == entry.original.slot }
                }
            }
        }

        // Process other active chunk tasks
        val chunkIter = activeChunks.iterator()
        while (chunkIter.hasNext()) {
            chunkIter.next()
            chunkIter.remove()
        }
    }

    /**
     * Check if a region has been loaded.
     */
    fun isRegionLoaded(regionId: Int): Boolean = regionId in loadedRegions

    /**
     * Mark a region as loaded.
     */
    fun markRegionLoaded(regionId: Int) {
        loadedRegions.add(regionId)
    }

    /**
     * Get all loaded chunks.
     */
    fun allChunks(): Collection<Chunk> = chunks.values

    /**
     * Get the number of loaded chunks.
     */
    fun chunkCount(): Int = chunks.size

    /**
     * Get the number of loaded regions.
     */
    fun regionCount(): Int = loadedRegions.size

    /**
     * Clear all chunks (for testing/restart).
     */
    fun clear() {
        chunks.clear()
        activeChunks.clear()
        loadedRegions.clear()
    }
}
