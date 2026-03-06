package org.darkan.core.map

import org.darkan.core.model.GroundItem
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.WorldObject
import world.gregs.voidps.type.Tile

/**
 * Represents an 8x8 tile chunk of the world.
 * Stores base objects (from cache), spawned objects (runtime), removed base objects, and entity indices.
 *
 * @param chunkId packed chunk ID: (chunkY) | (chunkX shl 11) | (level shl 22)
 */
class Chunk(val chunkId: Int) {

    /** Base objects loaded from cache, indexed by [localSlotIndex]. */
    val baseObjects = arrayOfNulls<WorldObject>(8 * 8 * 4) // localX * localY * slots

    /** Runtime-spawned objects in this chunk. */
    val spawnedObjects = mutableListOf<WorldObject>()

    /** Base objects that were removed or replaced (tracked for player init). */
    val removedBaseObjects = mutableSetOf<WorldObject>()

    /** Player indices present in this chunk. */
    val players = mutableSetOf<Int>()

    /** NPC indices present in this chunk. */
    val npcs = mutableSetOf<Int>()

    /** Ground items on tiles in this chunk. */
    val groundItems = mutableListOf<GroundItem>()

    /** Whether this chunk's region data has been loaded from the cache. */
    @Volatile var loaded = false

    val chunkX: Int get() = (chunkId shr 11) and 0x7FF
    val chunkY: Int get() = chunkId and 0x7FF
    val level: Int get() = (chunkId shr 22) and 0x3

    val baseX: Int get() = chunkX shl 3
    val baseY: Int get() = chunkY shl 3

    val baseTile: Tile get() = Tile(baseX, baseY, level)

    /**
     * Add a base object from cache data.
     */
    fun addBaseObject(obj: WorldObject) {
        val idx = localSlotIndex(obj.tile.x and 0x7, obj.tile.y and 0x7, obj.slot)
        baseObjects[idx] = obj
    }

    /**
     * Get the base object at the given local position and slot.
     */
    fun getBaseObject(localX: Int, localY: Int, slot: Int): WorldObject? {
        return baseObjects[localSlotIndex(localX, localY, slot)]
    }

    /**
     * Get the object at a tile with the given shape slot, checking spawned first then base.
     */
    fun getObject(tile: Tile, slot: Int): WorldObject? {
        val localX = tile.x and 0x7
        val localY = tile.y and 0x7
        // Check spawned objects first (override base)
        for (obj in spawnedObjects) {
            if (obj.tile == tile && obj.slot == slot) return obj
        }
        val base = baseObjects[localSlotIndex(localX, localY, slot)]
        if (base != null && base !in removedBaseObjects) return base
        return null
    }

    /**
     * Spawn a runtime object, clipping it into collision.
     */
    fun spawnObject(obj: WorldObject, clip: Boolean = true) {
        // Remove any existing base object at same slot
        val localX = obj.tile.x and 0x7
        val localY = obj.tile.y and 0x7
        val base = baseObjects[localSlotIndex(localX, localY, obj.slot)]
        if (base != null && base !in removedBaseObjects) {
            removedBaseObjects.add(base)
            if (clip) unclipObject(base)
        }
        // Remove any existing spawned object at same slot
        spawnedObjects.removeAll { it.tile == obj.tile && it.slot == obj.slot }
        spawnedObjects.add(obj)
        if (clip) clipObject(obj)
    }

    /**
     * Remove a spawned or base object.
     */
    fun removeObject(obj: WorldObject, clip: Boolean = true) {
        val localX = obj.tile.x and 0x7
        val localY = obj.tile.y and 0x7
        val idx = localSlotIndex(localX, localY, obj.slot)

        // Check if it's a spawned object
        if (spawnedObjects.remove(obj)) {
            if (clip) unclipObject(obj)
            return
        }
        // Otherwise remove the base object
        if (baseObjects[idx] == obj) {
            removedBaseObjects.add(obj)
            if (clip) unclipObject(obj)
        }
    }

    /**
     * Get a ground item matching the given ID and tile, visible to the specified player (or public).
     */
    fun getGroundItem(itemId: Int, tile: Tile, username: String?): GroundItem? {
        return groundItems.firstOrNull { gi ->
            gi.tile == tile && gi.id == itemId &&
                (gi.ownerUsername == null || gi.ownerUsername == username)
        }
    }

    /**
     * Get all ground items visible to a given player (public items + their private items).
     */
    fun getGroundItemsForPlayer(username: String?): List<GroundItem> {
        return groundItems.filter { it.ownerUsername == null || it.ownerUsername == username }
    }

    companion object {
        /**
         * Pack a chunk ID from world coordinates and level.
         */
        fun chunkId(x: Int, y: Int, level: Int): Int {
            return (y shr 3) or ((x shr 3) shl 11) or (level shl 22)
        }

        /**
         * Index into the baseObjects array: [localX][localY][slot] flattened.
         */
        private fun localSlotIndex(localX: Int, localY: Int, slot: Int): Int {
            return (localX and 0x7) + ((localY and 0x7) shl 3) + (slot shl 6)
        }

        /**
         * Clip an object into the world collision map.
         */
        fun clipObject(obj: WorldObject) {
            val def = obj.definitions
            WorldCollision.clipObject(
                tile = obj.tile,
                id = obj.id,
                shape = obj.type,
                rotation = obj.rotation,
                clipType = def.solid,
                blocksProjectiles = (def.block and ObjectDefinition.PROJECTILE) != 0,
                ignoresPathfinder = (def.block and ObjectDefinition.ROUTE) == 0,
                sizeX = def.sizeX,
                sizeY = def.sizeY
            )
        }

        /**
         * Unclip an object from the world collision map.
         */
        fun unclipObject(obj: WorldObject) {
            val def = obj.definitions
            WorldCollision.unclipObject(
                tile = obj.tile,
                id = obj.id,
                shape = obj.type,
                rotation = obj.rotation,
                clipType = def.solid,
                blocksProjectiles = (def.block and ObjectDefinition.PROJECTILE) != 0,
                ignoresPathfinder = (def.block and ObjectDefinition.ROUTE) == 0,
                sizeX = def.sizeX,
                sizeY = def.sizeY
            )
        }
    }
}

private typealias ObjectDefinition = world.gregs.voidps.cache.definition.data.ObjectDefinition
