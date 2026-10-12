package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.model.Animation
import world.gregs.voidps.type.Tile

private const val PLANKS_REQUIRED = 3
private const val PLACEMENT_KEY = "furniturePlacement"
private const val ROTATION_STEPS = 4
private const val PLACE_OPTION = "Place"
private const val PLACE_OP_NUM = 1
private const val ROTATE_OP_NUM = 2
private const val STEPS_PER_QUARTER_TURN = 1
private const val DEGREES_PER_STEP = 360 / ROTATION_STEPS
private val MENU_MATERIALS = ConstructionMaterials.PLANKS + ConstructionMaterials.NAIL_TYPES + ConstructionMaterials.CANDLES + ConstructionMaterials.BARS + intArrayOf(Obj.iron_ore, Obj.coal)

private fun ConstructionPiece.footprint(tile: Tile, rotation: Int): List<Tile> {
    val (across, along) = if (rotation and 1 == 0) width to depth else depth to width
    return (0 until across).flatMap { x -> (0 until along).map { y -> tile.transform(x, y) } }
}

private fun ConstructionPiece.fits(player: Player, tile: Tile, rotation: Int): Boolean =
    tile.plane == player.plane && footprint(tile, rotation).all { World.floorAndWallsFree(it, 1) }

private class Placement(val piece: ConstructionPiece, var ghost: GameObject? = null, var step: Int = 0) {
    val rotation get() = (piece.rotation + step / STEPS_PER_QUARTER_TURN) and 3
}

@ServerStartupEvent
fun mapFurnitureBuilding() {
    WorldDelta.track(*ConstructionCatalogue.pieces.flatMap { it.placedLocs.toList() }.toIntArray())
    onItemOnItem(intArrayOf(Obj.hammer), MENU_MATERIALS) { e -> ConstructionMenu.open(e.player) }
    onObjectClick(*ConstructionCatalogue.pieces.map { it.ghost }.toTypedArray()) { e -> if (e.opNum == ROTATE_OP_NUM) rotateGhost(e.player) }
    for (piece in ConstructionCatalogue.pieces)
        onItemClick(piece.item, options = arrayOf(PLACE_OPTION)) { e -> startPlacement(e.player, piece) }
    onLogin { (player) ->
        if (!player.inventory.containsItem(Obj.hammer)) player.inventory.addItemDrop(Obj.hammer)
        val missingPlanks = PLANKS_REQUIRED - player.inventory.getNumberOf(Obj.woodplank)
        if (missingPlanks > 0) player.inventory.addItemDrop(Obj.woodplank, missingPlanks)
    }
}

fun handleFurniturePlacementClick(player: Player, tile: Tile): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val ghost = placement.ghost
    if (ghost != null && placement.piece.footprint(ghost.tile, placement.rotation).contains(tile)) {
        build(player, placement, ghost.tile)
        return true
    }
    if (!placement.piece.fits(player, tile, placement.rotation)) {
        player.sendMessage("You can't build that there.")
        return true
    }
    if (!placement.piece.footprint(tile, placement.rotation).all { ClaimManager.canConstruct(player, it) })
        return true
    showGhost(player, placement, tile)
    player.sendMessage("Left-click the ghost to build it, right-click it to rotate, or click elsewhere to move it.")
    return true
}

fun handleGhostClick(player: Player, tile: Tile, objectId: Int, opNum: Int): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val ghost = placement.ghost ?: return false
    if (ghost.id != objectId || !ghost.tile.matches(tile)) return false
    when (opNum) {
        PLACE_OP_NUM -> build(player, placement, ghost.tile)
        ROTATE_OP_NUM -> rotateGhost(player)
    }
    return true
}

private fun showGhost(player: Player, placement: Placement, tile: Tile) {
    placement.ghost?.let(player.packets::sendRemoveObject)
    val piece = placement.piece
    placement.ghost = GameObject(piece.ghost, piece.shape, placement.rotation, tile).also(player.packets::sendAddObject)
}

private fun rotateGhost(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    val ghost = placement.ghost ?: return
    val turned = Placement(placement.piece, step = (placement.step + 1) % ROTATION_STEPS)
    if (!turned.piece.fits(player, ghost.tile, turned.rotation)) {
        player.sendMessage("There isn't room to turn it that way.")
        return
    }
    placement.step = turned.step
    showGhost(player, placement, ghost.tile)
    player.sendMessage("Rotation ${placement.step * DEGREES_PER_STEP} degrees.")
}

fun startPlacement(player: Player, piece: ConstructionPiece) {
    cancelPlacement(player)
    if (!ClaimManager.canConstruct(player, player.tile)) {
        player.closeInterfaces()
        return
    }
    player.closeInterfaces()
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(piece))
    player.sendMessage("Click the ground where you want to place the ${piece.name.lowercase()}.")
}

fun cancelPlacement(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    placement.ghost?.let(player.packets::sendRemoveObject)
    player.tempAttribs.removeO<Placement>(PLACEMENT_KEY)
}

private fun build(player: Player, placement: Placement, tile: Tile) {
    val piece = placement.piece
    val rotation = placement.rotation
    cancelPlacement(player)
    if (!player.inventory.containsItem(piece.item)) {
        player.sendMessage("You need a ${piece.name.lowercase()} to place.")
        return
    }
    if (!piece.fits(player, tile, rotation)) {
        player.sendMessage("You can't build that there.")
        return
    }
    if (!piece.footprint(tile, rotation).all { ClaimManager.canConstruct(player, it) }) return
    player.faceTile(tile)
    player.inventory.deleteItem(piece.item, 1)
    player.sendMessage("You place the ${piece.name.lowercase()}.")
    val built = GameObject(piece.loc, piece.shape, rotation, tile)
    initBuiltFurniture(built)
    World.spawnObject(built)
    WorldDelta.setOwner(built, player.username)
}

fun makePiece(player: Player, piece: ConstructionPiece, times: Int) {
    if (!ClaimManager.canConstruct(player, player.tile)) return
    var made = 0
    while (made < times && piece.canMake(player) && player.inventory.hasFreeSlots()) {
        piece.take(player)
        player.inventory.addItem(piece.item, 1)
        player.skills.addXp(Constants.CONSTRUCTION, piece.xp)
        made++
    }
    if (made == 0) {
        player.sendMessage(if (piece.canMake(player)) "You don't have enough inventory space." else "You need ${piece.materials()} to make that.")
        return
    }
    player.anim(Animation(Seq.human_poh_build_floor))
    player.sendMessage("You make ${if (made == 1) "a" else made.toString()} ${piece.name.lowercase()}. Use Place on it to set it down.")
}
