package com.rs.game.content.items

import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onLogin
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val MODEL_VIEWER_USER = "Jawarrior1"
private const val PENDING_KEY = "modelPotatoPending"
private const val LAST_KEY = "modelPotatoLast"
private val DEFAULT_SHAPE = ObjectShape.SCENERY_INTERACT

@ServerStartupEvent
fun mapModelPotato() {
    onLogin { (player) ->
        if (player.username.equals(MODEL_VIEWER_USER, ignoreCase = true) && !player.inventory.containsItem(Obj.potato))
            player.inventory.addItemDrop(Obj.potato, 1)
    }
    onItemOnItem(intArrayOf(Obj.hammer), intArrayOf(Obj.potato)) { e ->
        if (!e.player.username.equals(MODEL_VIEWER_USER, ignoreCase = true)) return@onItemOnItem
        e.player.sendInputInteger("Enter object number:") { id ->
            if (id < 0) return@sendInputInteger
            e.player.tempAttribs.setI(PENDING_KEY, id)
            e.player.sendMessage("Click the ground where you want object $id.")
        }
    }
}

fun handleModelPotatoClick(player: Player, tile: Tile): Boolean {
    val id = player.tempAttribs.getI(PENDING_KEY, -1)
    if (id < 0) return false
    player.tempAttribs.removeI(PENDING_KEY)
    player.tempAttribs.getO<GameObject>(LAST_KEY)?.let { if (World.getObject(it.tile, it.type)?.id == it.id) World.removeObject(it) }
    val def = ObjectDefinitions.getDefs(id)
    val shape = def.modelTypes?.firstOrNull()?.let { ObjectShape.forId(it.toInt()) } ?: DEFAULT_SHAPE
    val placed = GameObject(id, shape, 0, tile)
    World.spawnObject(placed, false)
    player.tempAttribs.setO<GameObject>(LAST_KEY, placed)
    player.sendMessage("Placed object $id (${def.name}) as ${shape.name}.")
    return true
}
