package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.game.map.Chunk
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val CHUNK_SHIFT = 3
private const val NORTH_OF_SPAWN = 1

object StartLodestone {
    val tile: Tile by lazy { Settings.getConfig().playerStartTile.transform(0, NORTH_OF_SPAWN, 0) }

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != tile.plane) return
        if (tile.x shr CHUNK_SHIFT != chunk.x || tile.y shr CHUNK_SHIFT != chunk.y) return
        chunk.addGeneratedObject(GameObject(Loc.wilderness_lodestone, ObjectShape.SCENERY_INTERACT, 0, tile))
    }
}
