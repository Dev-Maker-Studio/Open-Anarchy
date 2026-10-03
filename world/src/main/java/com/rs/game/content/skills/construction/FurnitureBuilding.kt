package com.rs.game.content.skills.construction

import com.rs.engine.dialogue.startConversation
import com.rs.engine.dialogue.statements.Statement
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onLogin
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.gameval.gen.Varc
import org.darkan.core.model.Animation
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val PLANKS_REQUIRED = 3
private const val PLACEMENT_KEY = "furniturePlacement"
private const val SLOT_COMPONENT_STRIDE = 7

private val CLOSE_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_55)
private val FIRST_SLOT_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_8)

private enum class Furniture(
    val menuItem: Int,
    val loc: Int,
    val ghost: Int,
    val shape: ObjectShape,
    val rotation: Int,
    val xp: Double,
) {
    CHAIR(Obj.poh_armchair_1, Loc.built_wooden_chair, Loc.poh_chair1_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 66.0),
    WALL(Obj.wooden_wall_furniture_item, Loc.built_wooden_wall, Loc.poh_timberwall_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
    DOOR(Obj.wooden_door_furniture_item, Loc.built_wooden_door, Loc.timberwall_doorl_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
}

private class Placement(val furniture: Furniture, var ghost: GameObject? = null)

private object FurnitureMenu : Statement {
    override fun send(player: Player) {
        val available = Furniture.entries.indices.sumOf { 1 shl (it + 1) }
        player.packets.sendVarc(Varc.varc_841, available)
        player.packets.sendItems(Inv.inv_398, Furniture.entries.map { Item(it.menuItem, 1) }.toTypedArray<Item?>())
        player.packets.setIFEvents(IFEvents(Component.interface_1306.component_1306_55, -1, -1).enableContinueButton())
        Furniture.entries.indices.forEach {
            player.packets.setIFEvents(IFEvents(Interface.interface_1306, FIRST_SLOT_BUTTON + it * SLOT_COMPONENT_STRIDE, 4, 4).enableContinueButton())
        }
        player.interfaceManager.sendInterface(Interface.interface_1306)
    }

    override fun getOptionId(componentId: Int): Int =
        if (componentId == CLOSE_BUTTON) Int.MAX_VALUE else (componentId - FIRST_SLOT_BUTTON) / SLOT_COMPONENT_STRIDE

    override fun close(player: Player) = player.closeInterfaces()
}

@ServerStartupEvent
fun mapFurnitureBuilding() {
    onItemOnItem(intArrayOf(Obj.hammer), intArrayOf(Obj.woodplank)) { e -> openFurnitureMenu(e.player) }
    onLogin { (player) ->
        if (!player.inventory.containsItem(Obj.hammer)) player.inventory.addItemDrop(Obj.hammer)
        val missingPlanks = PLANKS_REQUIRED - player.inventory.getNumberOf(Obj.woodplank)
        if (missingPlanks > 0) player.inventory.addItemDrop(Obj.woodplank, missingPlanks)
    }
}

fun handleFurniturePlacementClick(player: Player, tile: Tile): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val ghost = placement.ghost
    if (ghost != null && ghost.tile == tile) {
        build(player, placement.furniture, tile)
        return true
    }
    if (tile.plane != player.plane || !World.floorAndWallsFree(tile, 1)) {
        player.sendMessage("You can't build that there.")
        return true
    }
    ghost?.let(player.packets::sendRemoveObject)
    val furniture = placement.furniture
    placement.ghost = GameObject(furniture.ghost, furniture.shape, furniture.rotation, tile).also(player.packets::sendAddObject)
    player.sendMessage("Click the ghost again to build it, or click elsewhere to move it.")
    return true
}

private fun openFurnitureMenu(player: Player) {
    cancelPlacement(player)
    if (player.inventory.getNumberOf(Obj.woodplank) < PLANKS_REQUIRED) {
        player.sendMessage("You need $PLANKS_REQUIRED planks to build furniture.")
        return
    }
    player.startConversation {
        statementWithActions(FurnitureMenu, *Furniture.entries.map { furniture -> Runnable { startPlacement(player, furniture) } }.toTypedArray())
    }
}

private fun startPlacement(player: Player, furniture: Furniture) {
    player.closeInterfaces()
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(furniture))
    player.sendMessage("Click the ground where you want to place the ${ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()}.")
}

private fun cancelPlacement(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    placement.ghost?.let(player.packets::sendRemoveObject)
    player.tempAttribs.removeO<Placement>(PLACEMENT_KEY)
}

private fun build(player: Player, furniture: Furniture, tile: Tile) {
    cancelPlacement(player)
    if (player.inventory.getNumberOf(Obj.woodplank) < PLANKS_REQUIRED) {
        player.sendMessage("You need $PLANKS_REQUIRED planks to build furniture.")
        return
    }
    player.inventory.deleteItem(Obj.woodplank, PLANKS_REQUIRED)
    player.faceTile(tile)
    player.anim(Animation(Seq.human_poh_build_floor))
    val built = GameObject(furniture.loc, furniture.shape, furniture.rotation, tile)
    initBuiltFurniture(built)
    World.spawnObject(built)
    player.skills.addXp(Constants.CONSTRUCTION, furniture.xp)
    player.sendMessage("You build a ${ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()}.")
}
