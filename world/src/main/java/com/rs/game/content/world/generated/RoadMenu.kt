package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.content.items.Spade
import com.rs.game.map.Chunk
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.city.DugRoads
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape

private const val CHUNK_SHIFT = 3
private const val CHUNK_SIZE = 8
private const val GROUND = 0
private val COST = intArrayOf(Obj.tin_ore, Obj.copper_ore)

object RoadMenu {

    fun open(player: Player) {
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before digging.")
            return
        }
        player.stopAll()
        player.interfaceManager.sendInterface(Interface.road_menu)
    }

    fun spawn(chunk: Chunk) {
        if (chunk.plane != GROUND) return
        val minX = chunk.x shl CHUNK_SHIFT
        val minY = chunk.y shl CHUNK_SHIFT
        for ((x, y) in DugRoads.within(minX, minY, minX + CHUNK_SIZE - 1, minY + CHUNK_SIZE - 1)) chunk.addGeneratedObject(road(x, y))
    }

    fun digRoad(player: Player) {
        val tile = player.tile
        val refusal = when {
            tile.plane != GROUND -> "A road can only be dug at ground level."
            GeneratedWorld.inCity(tile.x, tile.y) -> "The city keeps its own streets."
            GeneratedWorld.onRoad(tile.x, tile.y) || DugRoads.contains(tile.x, tile.y) -> "There is already a road here."
            GeneratedWorld.generator.liquidTileAt(tile.x, tile.y) != null -> "A road cannot be dug here."
            else -> null
        }
        if (refusal != null) {
            player.sendMessage(refusal)
            return
        }
        if (!ClaimManager.canBuild(player, tile)) return
        val missing = COST.firstOrNull { !player.inventory.containsItem(it, 1) }
        if (missing != null) {
            player.sendMessage("You need ${COST.joinToString(" and ") { "1 ${ItemDefinitions.getDefs(it).name.lowercase()}" }} to lay a road tile.")
            return
        }
        COST.forEach { player.inventory.deleteItem(it, 1) }
        player.anim(Seq.seq_830)
        DugRoads.add(tile.x, tile.y)
        World.spawnObject(road(tile.x, tile.y))
        player.sendMessage("You pack the ore into the ground and lay a road tile.")
    }

    private fun road(x: Int, y: Int) = GameObject(Loc.dug_road, ObjectShape.GROUND_DECORATION, 0, x, y, GROUND)
}

@ServerStartupEvent
fun mapRoadMenu() {
    onButtonClick(Interface.road_menu) { e ->
        when (e.component) {
            Component.road_menu.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.road_menu.dig_road_button -> RoadMenu.digRoad(e.player)
            Component.road_menu.just_dig_button -> {
                e.player.interfaceManager.removeCentralInterface()
                Spade.dig(e.player)
            }
        }
    }
}
