package org.darkan.core.map

import org.darkan.core.map.pathfinder.getIndexInZone
import org.darkan.core.map.pathfinder.getZoneIndex
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

/**
 * Global collision flag map.
 * Stores collision flags per-zone in a flat array indexed by zone coordinate.
 * The pathfinder reads from [allFlags] directly.
 */
object WorldCollision {
    val allFlags: Array<IntArray?> = arrayOfNulls(2048 * 2048 * 4)

    fun getFlags(x: Int, y: Int, level: Int): Int {
        val zoneId = getZoneIndex(x, y, level)
        val zone = allFlags[zoneId] ?: return -1
        return zone[getIndexInZone(x, y)]
    }

    fun getFlags(tile: Tile): Int = getFlags(tile.x, tile.y, tile.level)

    fun addFlag(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
        val zone = allFlags[zoneId]!!
        val idx = getIndexInZone(x, y)
        zone[idx] = zone[idx] or flag
    }

    fun addFlag(tile: Tile, flag: Int) = addFlag(tile.x, tile.y, tile.level, flag)

    fun removeFlag(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        val zone = allFlags[zoneId] ?: return
        val idx = getIndexInZone(x, y)
        zone[idx] = zone[idx] and flag.inv()
    }

    fun removeFlag(tile: Tile, flag: Int) = removeFlag(tile.x, tile.y, tile.level, flag)

    fun setFlags(x: Int, y: Int, level: Int, flag: Int) {
        val zoneId = getZoneIndex(x, y, level)
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
        allFlags[zoneId]!![getIndexInZone(x, y)] = flag
    }

    fun clearZone(zoneId: Int) {
        allFlags[zoneId] = null
    }

    fun initZone(zoneId: Int) {
        if (allFlags[zoneId] == null) allFlags[zoneId] = IntArray(64)
    }

    // --- Convenience flag operations ---

    fun addBlockedTile(tile: Tile) = addFlag(tile, ClipFlag.PFBW_FLOOR.flag)
    fun removeBlockedTile(tile: Tile) = removeFlag(tile, ClipFlag.PFBW_FLOOR.flag)

    fun addBlockWalkAndProj(tile: Tile) = addFlag(tile, ClipFlag.PFBW_GROUND_DECO.flag)
    fun removeBlockWalkAndProj(tile: Tile) = removeFlag(tile, ClipFlag.PFBW_GROUND_DECO.flag)

    fun addClipNPC(tile: Tile) = addFlag(tile, ClipFlag.BW_NPC.flag)
    fun removeClipNPC(tile: Tile) = removeFlag(tile, ClipFlag.BW_NPC.flag)

    fun addClipPlayer(tile: Tile) = addFlag(tile, ClipFlag.BW_PLAYER.flag)
    fun removeClipPlayer(tile: Tile) = removeFlag(tile, ClipFlag.BW_PLAYER.flag)

    fun getClipNPC(tile: Tile): Boolean = ClipFlag.flagged(getFlags(tile), ClipFlag.BW_NPC)

    // --- Object clipping ---

    fun addObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX)
            for (tileY in tile.y until tile.y + sizeY)
                addFlag(tileX, tileY, tile.level, flag)
    }

    fun removeObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX)
            for (tileY in tile.y until tile.y + sizeY)
                removeFlag(tileX, tileY, tile.level, flag)
    }

    fun addWall(tile: Tile, type: ObjectShape, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> when (rotation) {
                0 -> { addFlag(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
                1 -> { addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                2 -> { addFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); addFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                3 -> { addFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> when (rotation) {
                0 -> { addFlag(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(-1, 1), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)) }
                1 -> { addFlag(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)); addFlag(tile.add(1, 1), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)) }
                2 -> { addFlag(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)); addFlag(tile.add(1, -1), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)) }
                3 -> { addFlag(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(-1, -1), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_WHOLE_CORNER -> when (rotation) {
                0 -> { addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                1 -> { addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); addFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                2 -> { addFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); addFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
                3 -> { addFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); addFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); addFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
            }
            else -> {}
        }
    }

    fun removeWall(tile: Tile, type: ObjectShape, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 1), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 1), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, -1), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, -1), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder)) }
            }
            ObjectShape.WALL_WHOLE_CORNER -> when (rotation) {
                0 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)) }
                1 -> { removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, 1), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)) }
                2 -> { removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(1, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)) }
                3 -> { removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(0, -1), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder)); removeFlag(tile.add(-1, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder)) }
            }
            else -> {}
        }
    }

    /**
     * Clip an object into the collision map based on its shape, rotation, and definition properties.
     */
    fun clipObject(tile: Tile, id: Int, shape: ObjectShape, rotation: Int, clipType: Int, blocksProjectiles: Boolean, ignoresPathfinder: Boolean, sizeX: Int, sizeY: Int) {
        if (id == -1 || clipType == 0) return
        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                addWall(tile, shape, rotation, blocksProjectiles, !ignoresPathfinder)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sx = if (rotation == 1 || rotation == 3) sizeY else sizeX
                val sy = if (rotation == 1 || rotation == 3) sizeX else sizeY
                addObject(tile, sx, sy, blocksProjectiles, !ignoresPathfinder)
            }
            ObjectShape.GROUND_DECORATION -> if (clipType == 1) addBlockWalkAndProj(tile)
            else -> {}
        }
    }

    fun unclipObject(tile: Tile, id: Int, shape: ObjectShape, rotation: Int, clipType: Int, blocksProjectiles: Boolean, ignoresPathfinder: Boolean, sizeX: Int, sizeY: Int) {
        if (id == -1 || clipType == 0) return
        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                removeWall(tile, shape, rotation, blocksProjectiles, !ignoresPathfinder)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sx = if (rotation == 1 || rotation == 3) sizeY else sizeX
                val sy = if (rotation == 1 || rotation == 3) sizeX else sizeY
                removeObject(tile, sx, sy, blocksProjectiles, !ignoresPathfinder)
            }
            ObjectShape.GROUND_DECORATION -> if (clipType == 1) removeBlockWalkAndProj(tile)
            else -> {}
        }
    }
}
