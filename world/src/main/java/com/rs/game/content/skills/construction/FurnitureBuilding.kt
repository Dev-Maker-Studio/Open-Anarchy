package com.rs.game.content.skills.construction

import com.rs.engine.dialogue.startConversation
import com.rs.engine.dialogue.statements.Statement
import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onObjectClick
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
private const val ROTATION_STEPS = 8
private const val PLACE_OPTION = "Place"
private const val FURNACE_WIDTH = 2
private const val FURNACE_DEPTH = 3
private const val STEPS_PER_QUARTER_TURN = 2
private const val DEGREES_PER_STEP = 360 / ROTATION_STEPS

private val CLOSE_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_55)
private val FIRST_SLOT_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_8)

private enum class Furniture(
    val menuItem: Int,
    val loc: Int,
    val ghost: Int,
    val shape: ObjectShape,
    val rotation: Int,
    val xp: Double,
    val width: Int = 1,
    val depth: Int = 1,
    val placedFromItem: Boolean = false,
) {
    CHAIR(Obj.poh_armchair_1, Loc.built_wooden_chair, Loc.poh_chair1_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 66.0),
    WALL(Obj.wooden_wall_furniture_item, Loc.built_wooden_wall, Loc.poh_timberwall_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
    DOOR(Obj.wooden_door_furniture_item, Loc.built_wooden_door, Loc.timberwall_doorl_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
    FURNACE(Obj.furnace_item, Loc.built_furnace, Loc.poh_furnace_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 0.0, FURNACE_WIDTH, FURNACE_DEPTH, true),
}

private val MENU_FURNITURE = Furniture.entries.filterNot { it.placedFromItem }

private fun Furniture.footprint(tile: Tile, rotation: Int): List<Tile> {
    val (across, along) = if (rotation and 1 == 0) width to depth else depth to width
    return (0 until across).flatMap { x -> (0 until along).map { y -> tile.transform(x, y) } }
}

private fun Furniture.fits(player: Player, tile: Tile, rotation: Int): Boolean =
    tile.plane == player.plane && footprint(tile, rotation).all { World.floorAndWallsFree(it, 1) }

private fun Furniture.isAffordable(player: Player): Boolean =
    if (placedFromItem) player.inventory.containsItem(menuItem) else player.inventory.getNumberOf(Obj.woodplank) >= PLANKS_REQUIRED

private fun Furniture.shortage(): String =
    if (placedFromItem) "You need a ${ItemDefinitions.getDefs(menuItem).name.lowercase()} to place." else "You need $PLANKS_REQUIRED planks to build furniture."

private class Placement(val furniture: Furniture, var ghost: GameObject? = null, var step: Int = 0) {
    val rotation get() = (furniture.rotation + step / STEPS_PER_QUARTER_TURN) and 3
}

private object FurnitureMenu : Statement {
    override fun send(player: Player) {
        val available = MENU_FURNITURE.indices.sumOf { 1 shl (it + 1) }
        player.packets.sendVarc(Varc.varc_841, available)
        player.packets.sendItems(Inv.inv_398, MENU_FURNITURE.map { Item(it.menuItem, 1) }.toTypedArray<Item?>())
        player.packets.setIFEvents(IFEvents(Component.interface_1306.component_1306_55, -1, -1).enableContinueButton())
        MENU_FURNITURE.indices.forEach {
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
    onObjectClick(*Furniture.entries.map { it.ghost }.toTypedArray()) { e -> if (e.opNum == 2) rotateGhost(e.player) }
    onItemClick(Obj.furnace_item, options = arrayOf(PLACE_OPTION)) { e -> startPlacement(e.player, Furniture.FURNACE) }
    onLogin { (player) ->
        if (!player.inventory.containsItem(Obj.hammer)) player.inventory.addItemDrop(Obj.hammer)
        val missingPlanks = PLANKS_REQUIRED - player.inventory.getNumberOf(Obj.woodplank)
        if (missingPlanks > 0) player.inventory.addItemDrop(Obj.woodplank, missingPlanks)
    }
}

fun handleFurniturePlacementClick(player: Player, tile: Tile): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val ghost = placement.ghost
    if (ghost != null && placement.furniture.footprint(ghost.tile, placement.rotation).contains(tile)) {
        build(player, placement, ghost.tile)
        return true
    }
    if (!placement.furniture.fits(player, tile, placement.rotation)) {
        player.sendMessage("You can't build that there.")
        return true
    }
    if (!placement.furniture.footprint(tile, placement.rotation).all { ClaimManager.canBuild(player, it) })
        return true
    showGhost(player, placement, tile)
    player.sendMessage("Left-click the ghost to build it, right-click it to rotate, or click elsewhere to move it.")
    return true
}

private fun showGhost(player: Player, placement: Placement, tile: Tile) {
    placement.ghost?.let(player.packets::sendRemoveObject)
    val furniture = placement.furniture
    placement.ghost = GameObject(furniture.ghost, furniture.shape, placement.rotation, tile).also(player.packets::sendAddObject)
}

private fun rotateGhost(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    val ghost = placement.ghost ?: return
    val turned = Placement(placement.furniture, step = (placement.step + 1) % ROTATION_STEPS)
    if (!turned.furniture.fits(player, ghost.tile, turned.rotation)) {
        player.sendMessage("There isn't room to turn it that way.")
        return
    }
    placement.step = turned.step
    showGhost(player, placement, ghost.tile)
    player.sendMessage("Rotation ${placement.step * DEGREES_PER_STEP} degrees.")
}

private fun openFurnitureMenu(player: Player) {
    cancelPlacement(player)
    if (player.inventory.getNumberOf(Obj.woodplank) < PLANKS_REQUIRED) {
        player.sendMessage("You need $PLANKS_REQUIRED planks to build furniture.")
        return
    }
    player.startConversation {
        statementWithActions(FurnitureMenu, *MENU_FURNITURE.map { furniture -> Runnable { startPlacement(player, furniture) } }.toTypedArray())
    }
}

private fun startPlacement(player: Player, furniture: Furniture) {
    cancelPlacement(player)
    player.closeInterfaces()
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(furniture))
    player.sendMessage("Click the ground where you want to place the ${ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()}.")
}

private fun cancelPlacement(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    placement.ghost?.let(player.packets::sendRemoveObject)
    player.tempAttribs.removeO<Placement>(PLACEMENT_KEY)
}

private fun build(player: Player, placement: Placement, tile: Tile) {
    val furniture = placement.furniture
    val rotation = placement.rotation
    cancelPlacement(player)
    if (!furniture.isAffordable(player)) {
        player.sendMessage(furniture.shortage())
        return
    }
    if (!furniture.fits(player, tile, rotation)) {
        player.sendMessage("You can't build that there.")
        return
    }
    val name = ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()
    player.faceTile(tile)
    if (furniture.placedFromItem) {
        player.inventory.deleteItem(furniture.menuItem, 1)
        player.sendMessage("You place the $name.")
    } else {
        player.inventory.deleteItem(Obj.woodplank, PLANKS_REQUIRED)
        player.anim(Animation(Seq.human_poh_build_floor))
        player.skills.addXp(Constants.CONSTRUCTION, furniture.xp)
        player.sendMessage("You build a $name.")
    }
    val built = GameObject(furniture.loc, furniture.shape, rotation, tile)
    initBuiltFurniture(built)
    World.spawnObject(built)
}
