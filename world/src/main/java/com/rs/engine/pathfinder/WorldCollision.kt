package com.rs.engine.pathfinder

import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.gameobject.GameObject
import com.rs.utils.blocks
import com.rs.utils.clipType
import com.rs.utils.ignoresPathfinder
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.map.ClipFlag
import org.darkan.core.map.WorldCollision
import org.darkan.core.map.pathfinder.Direction
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

object NpcCollision {
    @JvmStatic
    fun clipNPC(npc: NPC) {
        if (!npc.blocksOtherNpcs()) return
        val lastTile = if (npc.lastTile == null) npc.tile else npc.lastTile
        fillNPCClip(lastTile, npc.size, false)
        if (!npc.hasFinished()) fillNPCClip(npc.tile, npc.size, true)
    }

    @JvmStatic
    fun fillNPCClip(tile: Tile, size: Int, blocks: Boolean) {
        for (x in 0 until size) for (y in 0 until size) {
            val local = tile.transform(x, y)
            if (blocks) WorldCollision.addClipNPC(local)
            else WorldCollision.removeClipNPC(local)
        }
    }

    @JvmStatic
    fun getClipNPC(tile: Tile): Boolean {
        return WorldCollision.getClipNPC(tile)
    }

    @JvmStatic
    fun checkNPCClip(npc: NPC, dir: Direction): Boolean {
        val size = npc.size
        val toX = npc.x + dir.dx
        val toY = npc.y + dir.dy
        val eastMostX = npc.x + (size - 1)
        val northMostY = npc.y + (size - 1)
        for (x in toX until (toX + size)) for (y in toY until (toY + size)) {
            if (x >= npc.x && x <= eastMostX && y >= npc.y && y <= northMostY)
                continue
            if (getClipNPC(Tile.of(x, y, npc.plane))) return false
        }
        return true
    }
}

/**
 * GameObject-specific clip/unclip that delegates to core WorldCollision.
 * These methods reference game-specific types (GameObject, ObjectDefinitions).
 */
object GameObjectCollision {
    @JvmStatic
    fun clip(obj: GameObject) {
        if (obj.id == -1) return
        val type: ObjectShape = obj.type
        val rotation: Int = obj.rotation
        val defs = ObjectDefinitions.getDefs(obj.id)
        if (defs.clipType == 0) return

        when (type) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                WorldCollision.addWall(obj.tile, type, rotation, defs.blocks(), !defs.ignoresPathfinder)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sizeX: Int
                val sizeY: Int
                if (rotation != 1 && rotation != 3) {
                    sizeX = defs.sizeX
                    sizeY = defs.sizeY
                } else {
                    sizeX = defs.sizeY
                    sizeY = defs.sizeX
                }
                WorldCollision.addObject(obj.tile, sizeX, sizeY, defs.blocks(), !defs.ignoresPathfinder)
            }
            ObjectShape.GROUND_DECORATION -> if (defs.clipType == 1) WorldCollision.addBlockWalkAndProj(obj.tile)
            else -> {}
        }
    }

    @JvmStatic
    fun unclip(tile: Tile) {
        WorldCollision.setFlags(tile.x, tile.y, tile.level, 0)
    }

    @JvmStatic
    fun unclip(obj: GameObject) {
        if (obj.id == -1) return
        val type: ObjectShape = obj.type
        val rotation: Int = obj.rotation
        val defs = ObjectDefinitions.getDefs(obj.id)
        if (defs.clipType == 0) return

        when (type) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER ->
                WorldCollision.removeWall(obj.tile, type, rotation, defs.blocks(), !defs.ignoresPathfinder)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT,
            ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF,
            ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sizeX: Int
                val sizeY: Int
                if (rotation == 1 || rotation == 3) {
                    sizeX = defs.sizeY
                    sizeY = defs.sizeX
                } else {
                    sizeX = defs.sizeX
                    sizeY = defs.sizeY
                }
                WorldCollision.removeObject(obj.tile, sizeX, sizeY, defs.blocks(), !defs.ignoresPathfinder)
            }
            ObjectShape.GROUND_DECORATION -> if (defs.clipType == 1) WorldCollision.removeBlockWalkAndProj(obj.tile)
            else -> {}
        }
    }
}
