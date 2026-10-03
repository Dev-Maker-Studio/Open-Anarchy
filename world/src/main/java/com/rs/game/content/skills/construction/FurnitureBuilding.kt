package com.rs.game.content.skills.construction

import com.rs.engine.dialogue.startConversation
import com.rs.engine.dialogue.statements.Statement
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.InterfaceManager
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
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
import java.io.File

private const val PLANKS_REQUIRED = 3
private const val PLACEMENT_KEY = "furniturePlacement"
private const val SLOT_COMPONENT_STRIDE = 7
private const val ROTATION_STEPS = 8
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
) {
    CHAIR(Obj.poh_armchair_1, Loc.built_wooden_chair, Loc.poh_chair1_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, 66.0),
    WALL(Obj.wooden_wall_furniture_item, Loc.built_wooden_wall, Loc.poh_timberwall_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
    DOOR(Obj.wooden_door_furniture_item, Loc.built_wooden_door, Loc.timberwall_doorl_placement_ghost, ObjectShape.WALL_STRAIGHT, 3, 100.0),
}

private class Placement(val furniture: Furniture, var ghost: GameObject? = null, var step: Int = 0) {
    val rotation get() = (furniture.rotation + step / STEPS_PER_QUARTER_TURN) and 3
}

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
    onObjectClick(*Furniture.entries.map { it.ghost }.toTypedArray()) { e -> if (e.opNum == 2) rotateGhost(e.player) }
    onLogin { (player) ->
        if (!player.inventory.containsItem(Obj.hammer)) player.inventory.addItemDrop(Obj.hammer)
        val missingPlanks = PLANKS_REQUIRED - player.inventory.getNumberOf(Obj.woodplank)
        if (missingPlanks > 0) player.inventory.addItemDrop(Obj.woodplank, missingPlanks)
    }
}

private const val HOVER_SAMPLE_KEY = "placementHoverSample"
private const val HOVER_FROZEN_KEY = "placementHoverFrozenUntil"
private const val HOVER_MOVED_KEY = "placementHoverMovedAt"
private const val HOVER_FREEZE_MS = 6000L
private const val HOVER_MIN_INTERVAL_MS = 80L
private const val HOVER_MAX_TILES = 24.0
private val HOVER_LOG = File("./logs/placement-hover.log")

private const val CAMERA_DISTANCE = 10.0
private const val CAMERA_SCALE = 0.894
private const val CAMERA_FOCUS_HEIGHT = 1.562
private const val CAMERA_YAW_OFFSET = 0.128
private const val CAMERA_CENTRE_DROP = -0.041
private const val ANGLE_UNITS = 2048.0

private class HoverSample(val mouseX: Int, val mouseY: Int, val yaw: Int, val pitch: Int, val viewWidth: Int, val viewHeight: Int, val buttons: Int)

/**
 * The tile under the mouse, as an offset from the player's tile, projected from the camera. The client never reports the tile
 * under the mouse, so this inverts a fitted camera model (distance, scale, focus height) over the sampled mouse position, camera
 * yaw and pitch and the viewport size. It is accurate to about one tile; the click that places the furniture is exact.
 */
private fun hoveredOffset(sample: HoverSample): Pair<Int, Int>? {
    val yaw = -sample.yaw * 2 * Math.PI / ANGLE_UNITS + CAMERA_YAW_OFFSET
    val pitch = sample.pitch * 2 * Math.PI / ANGLE_UNITS
    val sinYaw = Math.sin(yaw)
    val cosYaw = Math.cos(yaw)
    val camera = doubleArrayOf(-CAMERA_DISTANCE * Math.cos(pitch) * sinYaw, -CAMERA_DISTANCE * Math.cos(pitch) * cosYaw, CAMERA_FOCUS_HEIGHT + CAMERA_DISTANCE * Math.sin(pitch))
    val forward = normalise(doubleArrayOf(-camera[0], -camera[1], CAMERA_FOCUS_HEIGHT - camera[2]))
    val right = normalise(doubleArrayOf(forward[1], -forward[0], 0.0))
    val up = doubleArrayOf(right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0])
    val scale = CAMERA_SCALE * sample.viewHeight
    val sx = (sample.mouseX - sample.viewWidth / 2.0) / scale
    val sy = -(sample.mouseY - (sample.viewHeight / 2.0 + CAMERA_CENTRE_DROP * sample.viewHeight)) / scale
    val ray = DoubleArray(3) { forward[it] + sx * right[it] + sy * up[it] }
    if (ray[2] >= -1e-6)
        return null
    val t = -camera[2] / ray[2]
    val x = camera[0] + t * ray[0]
    val y = camera[1] + t * ray[1]
    if (Math.hypot(x, y) > HOVER_MAX_TILES)
        return null
    return Math.round(x).toInt() to Math.round(y).toInt()
}

private fun normalise(v: DoubleArray): DoubleArray {
    val length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return DoubleArray(3) { v[it] / length }
}

private fun parseSample(text: String): HoverSample? {
    val f = text.removePrefix("~ph,").split(",").map { it.toIntOrNull() ?: return null }
    if (f.size < 11)
        return null
    return HoverSample(f[0], f[1], f[2], f[3], f[4], f[5], f[10])
}

fun onPlacementHoverSample(player: Player, text: String) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    val sample = parseSample(text) ?: return
    player.tempAttribs.setO<String>(HOVER_SAMPLE_KEY, text.removePrefix("~ph,"))
    val now = System.currentTimeMillis()
    if (sample.buttons != 0)
        player.tempAttribs.setL(HOVER_FROZEN_KEY, now + HOVER_FREEZE_MS)
    if (now < player.tempAttribs.getL(HOVER_FROZEN_KEY) || now - player.tempAttribs.getL(HOVER_MOVED_KEY) < HOVER_MIN_INTERVAL_MS)
        return
    if (sample.mouseX !in 0..sample.viewWidth || sample.mouseY !in 0..sample.viewHeight)
        return
    val (dx, dy) = hoveredOffset(sample) ?: return
    val tile = Tile.of(player.tile.x + dx, player.tile.y + dy, player.plane)
    if (placement.ghost?.tile == tile || !World.floorAndWallsFree(tile, 1))
        return
    player.tempAttribs.setL(HOVER_MOVED_KEY, now)
    showGhost(player, placement, tile)
}

private fun logHover(line: String) {
    synchronized(HOVER_LOG) { HOVER_LOG.appendText("${System.currentTimeMillis()} $line\n") }
}

fun handleFurniturePlacementClick(player: Player, tile: Tile): Boolean {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return false
    val last = player.tempAttribs.getO<String>(HOVER_SAMPLE_KEY)
    val predicted = last?.let { parseSample("~ph,$it") }?.let(::hoveredOffset)
    logHover("T ${player.username} ${tile.x} ${tile.y} ${tile.plane} last=$last at ${player.tile.x} ${player.tile.y} ${player.screenMode} predicted=${predicted?.let { "${player.tile.x + it.first},${player.tile.y + it.second}" }}")
    player.tempAttribs.setL(HOVER_FROZEN_KEY, 0L)
    val ghost = placement.ghost
    if (ghost != null && ghost.tile == tile) {
        build(player, placement, tile)
        return true
    }
    if (tile.plane != player.plane || !World.floorAndWallsFree(tile, 1)) {
        player.sendMessage("You can't build that there.")
        return true
    }
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
    placement.step = (placement.step + 1) % ROTATION_STEPS
    player.tempAttribs.setL(HOVER_FROZEN_KEY, 0L)
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
        statementWithActions(FurnitureMenu, *Furniture.entries.map { furniture -> Runnable { startPlacement(player, furniture) } }.toTypedArray())
    }
}

private fun startPlacement(player: Player, furniture: Furniture) {
    player.closeInterfaces()
    player.tempAttribs.setO<Placement>(PLACEMENT_KEY, Placement(furniture))
    player.interfaceManager.sendSub(InterfaceManager.Sub.MINIGAME_HUD, Interface.placement_hover)
    player.sendMessage("Click the ground where you want to place the ${ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()}.")
}

private fun cancelPlacement(player: Player) {
    val placement = player.tempAttribs.getO<Placement>(PLACEMENT_KEY) ?: return
    placement.ghost?.let(player.packets::sendRemoveObject)
    player.tempAttribs.removeO<Placement>(PLACEMENT_KEY)
    player.interfaceManager.removeSub(InterfaceManager.Sub.MINIGAME_HUD)
}

private fun build(player: Player, placement: Placement, tile: Tile) {
    val furniture = placement.furniture
    cancelPlacement(player)
    if (player.inventory.getNumberOf(Obj.woodplank) < PLANKS_REQUIRED) {
        player.sendMessage("You need $PLANKS_REQUIRED planks to build furniture.")
        return
    }
    player.inventory.deleteItem(Obj.woodplank, PLANKS_REQUIRED)
    player.faceTile(tile)
    player.anim(Animation(Seq.human_poh_build_floor))
    val built = GameObject(furniture.loc, furniture.shape, placement.rotation, tile)
    initBuiltFurniture(built)
    World.spawnObject(built)
    player.skills.addXp(Constants.CONSTRUCTION, furniture.xp)
    player.sendMessage("You build a ${ItemDefinitions.getDefs(furniture.menuItem).name.lowercase()}.")
}
