package com.rs.game.content.world.generated

import com.rs.game.map.Chunk
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.city.ChurchTemplate
import org.darkan.core.city.FarmTemplate
import org.darkan.core.city.Resident
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val CHUNK_SHIFT = 3
private const val CHUNK_SIZE = 8
private const val POST_RADIUS = 3
private const val PATROL_RADIUS = 12

object Cities {

    @JvmStatic
    fun covers(x: Int, y: Int): Boolean = GeneratedWorld.inCity(x, y)

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps) return
        val minX = chunk.x shl CHUNK_SHIFT
        val minY = chunk.y shl CHUNK_SHIFT
        for (city in GeneratedWorld.cities) {
            if (!city.touches(minX, minY, minX + CHUNK_SIZE - 1, minY + CHUNK_SIZE - 1)) continue
            for (piece in city.pieces()) {
                if (piece.level != chunk.plane || piece.x shr CHUNK_SHIFT != chunk.x || piece.y shr CHUNK_SHIFT != chunk.y) continue
                val shape = ObjectShape.forId(piece.shape) ?: continue
                chunk.addGeneratedObject(GameObject(piece.loc, shape, piece.rotation, piece.x, piece.y, piece.level))
            }
            city.residents.filter { it.plane == chunk.plane && it.x shr CHUNK_SHIFT == chunk.x && it.y shr CHUNK_SHIFT == chunk.y }.forEach(::spawnResident)
        }
    }

    private fun spawnResident(resident: Resident) {
        val tile = Tile.of(resident.x, resident.y, resident.plane)
        when (resident.role) {
            BuildingBuilder.BARBER -> BarberNPC(tile)
            BuildingBuilder.REALTOR -> CityRealtor(tile)
            BuildingBuilder.SHOPKEEPER -> CityShopkeeper(resident.npc, tile, CityShop.of(resident))
            ChurchTemplate.PRIEST, ChurchTemplate.CONGREGANT -> Churches.spawn(resident)
            BuildingBuilder.BANKER, FarmTemplate.LEPRECHAUN -> CityResident(resident.npc, tile)
            else -> CityGuard(resident.npc, tile, if (resident.role == BuildingBuilder.PATROL) PATROL_RADIUS else POST_RADIUS)
        }
    }
}
