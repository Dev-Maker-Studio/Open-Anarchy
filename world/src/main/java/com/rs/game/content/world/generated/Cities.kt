package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.map.Chunk
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.city.Resident
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.pathfinder.Direction
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val CHUNK_SHIFT = 3
private const val CHUNK_SIZE = 8
private const val GROUND = 0
private const val BANKER = "banker"
private const val PATROL = "patrol"
private const val POST_RADIUS = 3
private const val PATROL_RADIUS = 12

object Cities {

    @JvmStatic
    fun covers(x: Int, y: Int): Boolean = GeneratedWorld.inCity(x, y)

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND) return
        val minX = chunk.x shl CHUNK_SHIFT
        val minY = chunk.y shl CHUNK_SHIFT
        for (city in GeneratedWorld.cities) {
            if (!city.touches(minX, minY, minX + CHUNK_SIZE - 1, minY + CHUNK_SIZE - 1)) continue
            for (piece in city.buildings.flatMap { it.pieces }) {
                if (piece.x shr CHUNK_SHIFT != chunk.x || piece.y shr CHUNK_SHIFT != chunk.y) continue
                val shape = ObjectShape.forId(piece.shape) ?: continue
                chunk.addGeneratedObject(GameObject(piece.loc, shape, piece.rotation, piece.x, piece.y, GROUND))
            }
            city.residents.filter { it.x shr CHUNK_SHIFT == chunk.x && it.y shr CHUNK_SHIFT == chunk.y }.forEach(::spawnResident)
        }
    }

    private fun spawnResident(resident: Resident) {
        val tile = Tile.of(resident.x, resident.y, GROUND)
        if (resident.role == BANKER) World.spawnNPC(resident.npc, tile, Direction.SOUTH, false, true, null)
        else CityGuard(resident.npc, tile, if (resident.role == PATROL) PATROL_RADIUS else POST_RADIUS)
    }
}
