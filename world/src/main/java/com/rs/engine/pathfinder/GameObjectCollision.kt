package com.rs.engine.pathfinder

import com.rs.game.model.gameobject.GameObject
import com.rs.utils.blocks
import com.rs.utils.clipType
import com.rs.utils.ignoresPathfinder
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.map.WorldCollision

object GameObjectCollision {
    @JvmStatic
    fun clip(obj: GameObject) {
        if (obj.id == -1) return
        val defs = ObjectDefinitions.getDefs(obj.id)
        WorldCollision.clipObject(obj.tile, obj.id, obj.type, obj.rotation, defs.clipType, defs.blocks(), defs.ignoresPathfinder, defs.sizeX, defs.sizeY)
    }

    @JvmStatic
    fun unclip(obj: GameObject) {
        if (obj.id == -1) return
        val defs = ObjectDefinitions.getDefs(obj.id)
        WorldCollision.unclipObject(obj.tile, obj.id, obj.type, obj.rotation, defs.clipType, defs.blocks(), defs.ignoresPathfinder, defs.sizeX, defs.sizeY)
    }
}
