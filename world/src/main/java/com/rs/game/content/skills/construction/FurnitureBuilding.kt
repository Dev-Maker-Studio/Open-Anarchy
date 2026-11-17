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
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val PLANKS_REQUIRED = 3
private const val PLACEMENT_KEY = "furniturePlacement"
private const val ROTATION_STEPS = 4
private const val PLACE_OPTION = "Place"
private const val PLACE_ABOVE_OPTION = "Place above"
private const val PLACE_OP_NUM = 1
private const val ROTATE_OP_NUM = 2
private const val DEGREES_PER_STEP = 360 / ROTATION_STEPS
private val MENU_MATERIALS = ConstructionMaterials.PLANKS + ConstructionMaterials.NAIL_TYPES + ConstructionMaterials.CANDLES + ConstructionMaterials.BARS + intArrayOf(Obj.iron_ore, Obj.coal, Obj.clay, Obj.logs)

private fun ConstructionPiece.footprint(tile: Tile, rotation: Int): List<Tile> {
    val (across, along) = if (rotation and 1 == 0) width to depth else depth to width
    return (0 until across).flatMap { x -> (0 until along).map { y -> tile.transform(x, y) } }
}

private class Placement(val piece: ConstructionPiece, val raised: Boolean, val continued: Boolean = false, var anchor: Tile? = null, var ghost: GameObject? = null, var step: Int = 0) {
    val steps get() = if (piece.hasCorner) ROTATION_STEPS * 2 else ROTATION_STEPS
    val corner get() = step >= ROTATION_STEPS
    val rotation get() = (piece.rotation + step) and 3
    val shape get() = if (corner) ObjectShape.WALL_WHOLE_CORNER else piece.shape

    fun target(tile: Tile): Tile = if (raised) tile.transform(0, 0, 1) else tile

    fun footprint(tile: Tile): List<Tile> = piece.footprint(target(tile), rotation)

    fun fits(player: Player, tile: Tile): Boolean {
        val target = target(tile)
        if (tile.plane != player.plane || target.plane > TOP_PLANE) return false
        return when (piece.kind) {
            PieceKind.ROOF, PieceKind.FLOOR -> World.getObjectWithSlot(target, piece.shape.slot) == null
            PieceKind.LADDER -> target.plane < TOP_PLANE && World.floorAndWallsFree(target, 1) && World.getObjectWithSlot(target.transform(0, 0, 1), piece.shape.slot) == null
            PieceKind.STANDARD -> footprint(tile).all { World.floorAndWallsFree(it, 1) }
        }
    }

    fun allowed(player: Player, tile: Tile): Boolean = footprint(tile).all { ClaimManager.canConstruct(player, it, !continued) }
}

@ServerStartupEvent
fun mapFurnitureBuilding() {
    WorldDelta.track(*ConstructionCatalogue.pieces.flatMap { it.placedLocs.toList() }.toIntArray())
    onItemOnItem(intArrayOf(Obj.hammer), MENU_MATERIALS) { e -> ConstructionMenu.open(e.player) }
    onObjectClick(*ConstructionCatalogue.pieces.map { it.ghost }.toTypedArray()) { e -> if (e.opNum == ROTATE_OP_NUM) rotateGhost(e.player) }
    for (piece in ConstructionCatalogue.pieces)
        onItemClick(piece.item, options = arrayOf(PLACE_OPTION, PLACE_ABOVE_OPTION)) { e -> startPlacement(e.player, piece, e.option == PLACE_ABOVE_OPTION) }
    onLogin { (player) ->
        if (!player.inventory.containsItem(Obj.hammer)) player.inventory.addItemDrop(Obj.hammer)
        val missingPlanks = PLANKS_REQUIRED - player.inventory.getNumberOf(Obj.woodplank)
        if (missingPlanks > 0) player.inventory.addItemDrop(Obj.woodplank, missingPlanks)
    }
}

fun handleFurniturePlacementClick(player: Player, tile: Tile): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val anchor = placement.anchor
    if (anchor != null && placement.piece.footprint(anchor, placement.rotation).contains(tile)) {
        build(player, placement, anchor)
        return true
    }
    if (placement.continued) {
        if (placement.fits(player, tile) && placement.allowed(player, tile)) {
            build(player, placement, tile)
            return true
        }
        cancelPlacement(player)
        player.sendMessage("You stop building.")
        return false
    }
    if (!placement.fits(player, tile)) {
        player.sendMessage("You can't build that there.")
        return true
    }
    if (!placement.allowed(player, tile))
        return true
    showGhost(player, placement, tile)
    player.sendMessage(if (placement.raised) "Click the same spot again to build it on the level above, or click elsewhere to move it." else "Left-click the ghost to build it, right-click it to rotate, or click elsewhere to move it.")
    return true
}

fun handleGhostClick(player: Player, tile: Tile, objectId: Int, opNum: Int): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val ghost = placement.ghost ?: return false
    val anchor = placement.anchor ?: return false
    if (ghost.id != objectId || ghost.x != tile.x || ghost.y != tile.y) return false
    when (opNum) {
        PLACE_OP_NUM -> build(player, placement, anchor)
        ROTATE_OP_NUM -> rotateGhost(player)
    }
    return true
}

private fun showGhost(player: Player, placement: Placement, tile: Tile) {
    placement.ghost?.let(player.packets::sendRemoveObject)
    placement.anchor = tile
    placement.ghost = GameObject(placement.piece.ghost, placement.shape, placement.rotation, placement.target(tile)).also(player.packets::sendAddObject)
}

private fun rotateGhost(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    val anchor = placement.anchor ?: return
    val turned = Placement(placement.piece, placement.raised, step = (placement.step + 1) % placement.steps)
    if (!turned.fits(player, anchor)) {
        player.sendMessage("There isn't room to turn it that way.")
        return
    }
    placement.step = turned.step
    showGhost(player, placement, anchor)
    player.sendMessage("${if (placement.corner) "Corner piece, rotation" else "Rotation"} ${placement.step % ROTATION_STEPS * DEGREES_PER_STEP} degrees.")
}

fun startPlacement(player: Player, piece: ConstructionPiece, above: Boolean = false) {
    cancelPlacement(player)
    if (!ClaimManager.canConstruct(player, player.tile)) {
        player.closeInterfaces()
        return
    }
    player.closeInterfaces()
    val raised = above || piece.kind == PieceKind.ROOF
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(piece, raised))
    player.sendMessage(if (raised) "Click the ground beneath where you want the ${piece.name.lowercase()}; it is built on the level above you." else "Click the ground where you want to place the ${piece.name.lowercase()}.")
}

fun cancelPlacement(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    placement.ghost?.let(player.packets::sendRemoveObject)
    player.tempAttribs.removeO<Placement>(PLACEMENT_KEY)
}

private fun build(player: Player, placement: Placement, tile: Tile) {
    val piece = placement.piece
    cancelPlacement(player)
    if (!player.inventory.containsItem(piece.item)) {
        player.sendMessage("You need a ${piece.name.lowercase()} to place.")
        return
    }
    if (!placement.fits(player, tile)) {
        player.sendMessage("You can't build that there.")
        return
    }
    if (!placement.allowed(player, tile)) return
    player.faceTile(tile)
    player.inventory.deleteItem(piece.item, 1)
    player.sendMessage(if (piece.kind == PieceKind.ROOF) "You place the ${piece.name.lowercase()}. Roofs are hidden while you stand beneath them." else "You place the ${piece.name.lowercase()}.")
    val built = GameObject(piece.loc, placement.shape, placement.rotation, placement.target(tile))
    initBuiltFurniture(built)
    World.spawnObject(built)
    WorldDelta.setOwner(built, player.username)
    BuiltRoofs.built(built, piece, player.username)
    if (piece.kind != PieceKind.ROOF && piece.kind != PieceKind.FLOOR || !player.inventory.containsItem(piece.item)) return
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(piece, placement.raised, continued = true))
    if (!placement.continued) player.sendMessage("Keep clicking to lay more, or click anywhere you can't build to stop.")
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
