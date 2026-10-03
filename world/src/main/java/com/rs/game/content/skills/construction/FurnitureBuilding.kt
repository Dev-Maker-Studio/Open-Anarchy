package com.rs.game.content.skills.construction

import com.rs.engine.dialogue.startConversation
import com.rs.engine.dialogue.statements.Statement
import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.cache.ObjectDefinitions
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
private const val XP_PER_MATERIAL = 5.0
private const val MORE_ICON = 25525
private const val PAGE_SIZE = 7
private const val PLACEMENT_KEY = "furniturePlacement"
private const val SLOT_COMPONENT_STRIDE = 7
private const val ROTATION_STEPS = 4
private const val PLACE_OPTION = "Place"
private const val IRON_ORE = 440
private const val COAL = 453
private val MATERIALS = intArrayOf(Obj.woodplank, 1539, 2351, 2353, 2357, 36, IRON_ORE, COAL)
private const val FURNACE_WIDTH = 2
private const val FURNACE_DEPTH = 3
private const val STEPS_PER_QUARTER_TURN = 1
private const val DEGREES_PER_STEP = 360 / ROTATION_STEPS

private val CLOSE_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_55)
private val FIRST_SLOT_BUTTON = Utils.componentIdFromHash(Component.interface_1306.component_1306_8)

private enum class Category(val label: String, val icon: Int) {
    DOORS("Doors", 25518),
    WALLS("Walls", 25519),
    FENCES("Fences", 25520),
    FURNITURE("Furniture", 25521),
    ACCESSORIES("Accessories", 25522),
    STAIRS("Stairs", 25523),
    LIGHT("Light", 25524),
}

private class Furniture(
    val menuItem: Int,
    val loc: Int,
    val ghost: Int,
    val shape: ObjectShape,
    val rotation: Int,
    val xp: Double,
    val width: Int = 1,
    val depth: Int = 1,
    val placedFromItem: Boolean = false,
    val category: Category? = null,
    val cost: List<Pair<Int, Int>> = emptyList(),
) {
    /** A piece built from materials into a carried item, which is then placed and can be picked up again. */
    val crafted get() = cost.isNotEmpty()

    companion object {
        val CHAIR = Furniture(Obj.poh_armchair_1, Loc.built_wooden_chair, Loc.poh_chair1_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 66.0, category = Category.FURNITURE)
        val DOOR = Furniture(Obj.wooden_door_furniture_item, Loc.built_wooden_door, Loc.timberwall_doorl_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0, category = Category.DOORS)
        val FURNACE = Furniture(Obj.furnace_item, Loc.built_furnace, Loc.poh_furnace_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 60.0, FURNACE_WIDTH, FURNACE_DEPTH, true, Category.ACCESSORIES, listOf(IRON_ORE to 10, COAL to 2))

        /** An object from the map's own definitions, shaped and sized like it; [type] is its first model type. */
        private fun piece(item: Int, built: Int, ghost: Int, source: Int, category: Category, type: Int, label: String, vararg cost: Pair<Int, Int>): Furniture {
            val def = ObjectDefinitions.getDefs(source)
            val shape = ObjectShape.forId(type) ?: ObjectShape.SCENERY_INTERACT
            val quarterTurn = if (shape == ObjectShape.WALL_STRAIGHT) 3 else 0
            return Furniture(item, built, ghost, shape, quarterTurn, cost.sumOf { it.second } * XP_PER_MATERIAL, def.sizeX, def.sizeY, true, category, cost.toList())
        }

        val ALL: List<Furniture> = listOf(CHAIR, DOOR, FURNACE,
        piece(25498, 73933, 73932, 3, Category.DOORS, 0, "Door", 960 to 3),
        piece(25499, 73935, 73934, 37, Category.DOORS, 0, "Iron gate", 2351 to 3, 1539 to 10),
        piece(25500, 73937, 73936, 59, Category.DOORS, 0, "Steel door", 2353 to 3, 1539 to 10),
        piece(25501, 73939, 73938, 37, Category.FENCES, 0, "Wooden gate", 960 to 4),
        piece(25502, 73941, 73940, 33, Category.ACCESSORIES, 4, "Lever", 2351 to 1),
        piece(25503, 73943, 73942, 131, Category.FURNITURE, 0, "Bar", 960 to 5),
        piece(25504, 73945, 73944, 132, Category.FURNITURE, 10, "Bar", 960 to 5),
        piece(25505, 73947, 73946, 136, Category.FURNITURE, 10, "Bar", 960 to 5),
        piece(25506, 73949, 73948, 350, Category.FURNITURE, 10, "Drawers", 960 to 4),
        piece(25507, 73951, 73950, 367, Category.FURNITURE, 10, "Cupboard", 960 to 3),
        piece(25508, 73953, 73952, 373, Category.FURNITURE, 10, "Dresser", 960 to 4),
        piece(25509, 73955, 73954, 380, Category.FURNITURE, 10, "Bookcase", 960 to 4),
        piece(25510, 73957, 73956, 381, Category.FURNITURE, 10, "Bookcase", 960 to 4),
        piece(25511, 73959, 73958, 419, Category.FURNITURE, 10, "Bed", 960 to 4),
        piece(25512, 73961, 73960, 424, Category.FURNITURE, 10, "Large bed", 2357 to 3, 1539 to 8),
        piece(25513, 73963, 73962, 540, Category.FURNITURE, 10, "Workbench", 960 to 5, 1539 to 5),
        piece(25514, 73965, 73964, 591, Category.FURNITURE, 10, "Bank table", 960 to 5, 1539 to 5),
        piece(25515, 73967, 73966, 593, Category.FURNITURE, 10, "Table", 960 to 5),
        piece(25516, 73969, 73968, 36073, Category.WALLS, 0, "Wall", 960 to 4),
        piece(25517, 73971, 73970, 202, Category.LIGHT, 10, "Candles", 36 to 3, 960 to 1),
        )
    }
}

private fun Category.pieces(): List<Furniture> = Furniture.ALL.filter { it.category == this }

private fun Furniture.footprint(tile: Tile, rotation: Int): List<Tile> {
    val (across, along) = if (rotation and 1 == 0) width to depth else depth to width
    return (0 until across).flatMap { x -> (0 until along).map { y -> tile.transform(x, y) } }
}

private fun Furniture.fits(player: Player, tile: Tile, rotation: Int): Boolean =
    tile.plane == player.plane && footprint(tile, rotation).all { World.floorAndWallsFree(it, 1) }

private fun Furniture.isAffordable(player: Player): Boolean = when {
    placedFromItem -> player.inventory.containsItem(menuItem)
    else -> player.inventory.getNumberOf(Obj.woodplank) >= PLANKS_REQUIRED
}

private fun Furniture.shortage(): String =
    if (placedFromItem) "You need a ${ItemDefinitions.getDefs(menuItem).name.lowercase()} to place." else "You need $PLANKS_REQUIRED planks to build furniture."

private fun Furniture.canCraft(player: Player): Boolean = cost.all { (item, amount) -> player.inventory.getNumberOf(item) >= amount }

private fun Furniture.materials(): String = cost.joinToString(", ") { (item, amount) -> "$amount x ${ItemDefinitions.getDefs(item).name.lowercase()}" }

private class Placement(val furniture: Furniture, var ghost: GameObject? = null, var step: Int = 0) {
    val rotation get() = (furniture.rotation + step / STEPS_PER_QUARTER_TURN) and 3
}

private class FurnitureMenu(private val icons: List<Int>) : Statement {
    override fun send(player: Player) {
        val available = icons.indices.sumOf { 1 shl (it + 1) }
        player.packets.sendVarc(Varc.varc_841, available)
        player.packets.sendItems(Inv.inv_398, icons.map { Item(it, 1) }.toTypedArray<Item?>())
        player.packets.setIFEvents(IFEvents(Component.interface_1306.component_1306_55, -1, -1).enableContinueButton())
        icons.indices.forEach {
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
    onItemOnItem(intArrayOf(Obj.hammer), MATERIALS) { e -> openFurnitureMenu(e.player) }
    onObjectClick(*Furniture.ALL.map { it.ghost }.toTypedArray()) { e -> if (e.opNum == 2) rotateGhost(e.player) }
    onItemClick(Obj.furnace_item, options = arrayOf(PLACE_OPTION)) { e -> startPlacement(e.player, Furniture.FURNACE) }
    for (piece in Furniture.ALL.filter { it.crafted && it !== Furniture.FURNACE })
        onItemClick(piece.menuItem, options = arrayOf(PLACE_OPTION)) { e -> startPlacement(e.player, piece) }
    onObjectClick(*Furniture.ALL.filter { it.crafted && it !== Furniture.FURNACE }.map { it.loc }.toTypedArray()) { e -> if (e.option == PICK_UP_OPTION) pickUpPiece(e.player, e.obj) }
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
    if (!placement.furniture.footprint(tile, placement.rotation).all { ClaimManager.canConstruct(player, it) })
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
    if (!ClaimManager.canConstruct(player, player.tile)) return
    val categories = Category.entries
    player.startConversation {
        statementWithActions(FurnitureMenu(categories.map { it.icon }), *categories.map { category -> Runnable { openCategory(player, category, 0) } }.toTypedArray())
    }
}

private fun openCategory(player: Player, category: Category, page: Int) {
    val pieces = category.pieces()
    if (pieces.isEmpty()) {
        player.sendMessage("There is nothing to build in ${category.label.lowercase()} yet.")
        return
    }
    val start = page * (PAGE_SIZE - 1)
    val paged = pieces.size > PAGE_SIZE
    val shown = if (paged) pieces.drop(start).take(PAGE_SIZE - 1) else pieces
    val hasMore = paged && start + shown.size < pieces.size
    val icons = shown.map { it.menuItem } + if (paged) listOf(MORE_ICON) else emptyList()
    val actions = shown.map { piece -> Runnable { choose(player, piece) } } + if (paged) listOf(Runnable { openCategory(player, category, if (hasMore) page + 1 else 0) }) else emptyList()
    WorldTasks.schedule(1) { player.startConversation { statementWithActions(FurnitureMenu(icons), *actions.toTypedArray()) } }
}

private fun choose(player: Player, piece: Furniture) {
    if (!piece.crafted) {
        if (player.inventory.getNumberOf(Obj.woodplank) < PLANKS_REQUIRED) player.sendMessage("You need $PLANKS_REQUIRED planks to build furniture.") else startPlacement(player, piece)
        return
    }
    player.closeInterfaces()
    if (!ClaimManager.canConstruct(player, player.tile)) return
    if (!piece.canCraft(player)) {
        player.sendMessage("You need ${piece.materials()} to make that.")
        return
    }
    piece.cost.forEach { (item, amount) -> player.inventory.deleteItem(item, amount) }
    player.inventory.addItemDrop(piece.menuItem, 1)
    player.anim(Animation(Seq.human_poh_build_floor))
    player.skills.addXp(Constants.CONSTRUCTION, piece.xp)
    player.sendMessage("You make a ${ItemDefinitions.getDefs(piece.menuItem).name.lowercase()}. Use Place on it to set it down.")
}

/** Puts a placed piece back into the inventory as its item. */
private fun pickUpPiece(player: Player, obj: GameObject) {
    val piece = Furniture.ALL.firstOrNull { it.crafted && it.loc == obj.id } ?: return
    if (World.getObject(obj.tile, obj.type)?.id != obj.id) return
    if (!ClaimManager.canConstruct(player, obj.tile)) return
    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("You don't have enough inventory space to pick that up.")
        return
    }
    World.removeObject(obj)
    player.inventory.addItem(piece.menuItem, 1)
    player.sendMessage("You pick up the ${ItemDefinitions.getDefs(piece.menuItem).name.lowercase()}.")
}

private fun startPlacement(player: Player, furniture: Furniture) {
    cancelPlacement(player)
    if (!ClaimManager.canConstruct(player, player.tile)) {
        player.closeInterfaces()
        return
    }
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
    if (!furniture.footprint(tile, rotation).all { ClaimManager.canConstruct(player, it) }) return
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
