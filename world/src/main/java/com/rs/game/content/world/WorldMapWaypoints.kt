package com.rs.game.content.world

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Varcstr
import org.darkan.core.gameval.gen.Varp
import world.gregs.voidps.type.Tile
import kotlin.math.abs

private const val WAYPOINT_BUTTON_INDEX = 63
private const val WAYPOINT_ADD_OP = 1
private const val WAYPOINT_CLEAR_OP = 2
private const val WAYPOINT_REMOVE_RADIUS_TILES = 6
private const val MODE_OFF = 0
private const val MODE_ON = 1
private const val UNSET = 0
private const val NAMES_KEY = "worldMapWaypointNames"
private const val NAME_SEPARATOR = "|"
private const val NAME_WIDTH = 16
private const val CHILDREN_PER_WAYPOINT = 3
private const val OP_RENAME = 1
private const val OP_DELETE = 2
private val FORBIDDEN_NAME_CHARACTERS = Regex("[|<>]")

val WORLD_MAP_WAYPOINT_BUTTON = Component.interface_755.component_755_0 + WAYPOINT_BUTTON_INDEX

private val WAYPOINT_VARPS = intArrayOf(
    Varp.worldmap_waypoint_0, Varp.worldmap_waypoint_1, Varp.worldmap_waypoint_2, Varp.worldmap_waypoint_3,
    Varp.worldmap_waypoint_4, Varp.worldmap_waypoint_5, Varp.worldmap_waypoint_6, Varp.worldmap_waypoint_7,
)

@ServerStartupEvent
fun mapWorldMapWaypointNames() {
    onLogin { (player) -> pushWaypointNames(player) }
}

fun worldMapWaypointMenu(player: Player, childSlot: Int, option: Int) {
    val index = childSlot / CHILDREN_PER_WAYPOINT
    if (index !in WAYPOINT_VARPS.indices || player.vars.getVar(WAYPOINT_VARPS[index]) == UNSET)
        return
    when (option) {
        OP_RENAME -> player.sendInputName("Name for waypoint ${index + 1}:") { entered ->
            setWaypointName(player, index, entered)
            refreshWaypoint(player, index)
        }
        OP_DELETE -> removeWaypoint(player, index)
    }
}

private fun removeWaypoint(player: Player, index: Int) {
    setWaypointName(player, index, "")
    player.vars.saveVar(WAYPOINT_VARPS[index], UNSET)
}

private fun refreshWaypoint(player: Player, index: Int) {
    pushWaypointNames(player)
    player.vars.setVar(WAYPOINT_VARPS[index], player.vars.getVar(WAYPOINT_VARPS[index]), forceSend = true)
}

private fun waypointNames(player: Player): MutableList<String> {
    val stored: String = player.getO(NAMES_KEY) ?: ""
    val names = stored.split(NAME_SEPARATOR).toMutableList()
    while (names.size < WAYPOINT_VARPS.size) names += ""
    return names
}

private fun setWaypointName(player: Player, index: Int, name: String) {
    val names = waypointNames(player)
    names[index] = name.replace(FORBIDDEN_NAME_CHARACTERS, "").trim().take(NAME_WIDTH)
    player.set(NAMES_KEY, names.joinToString(NAME_SEPARATOR))
}

private fun pushWaypointNames(player: Player) {
    val names = waypointNames(player)
    val packed = WAYPOINT_VARPS.indices.joinToString("") { index ->
        names[index].ifEmpty { "Waypoint ${index + 1}" }.padEnd(NAME_WIDTH)
    }
    player.packets.sendVarcString(Varcstr.worldmap_waypoint_names, packed)
}

fun worldMapWaypointOption(player: Player, option: Int) {
    when (option) {
        WAYPOINT_ADD_OP -> {
            val adding = player.vars.getVar(Varp.worldmap_waypoint_mode) == MODE_ON
            player.vars.setVar(Varp.worldmap_waypoint_mode, if (adding) MODE_OFF else MODE_ON)
            if (!adding)
                player.sendMessage("Click the world map to place a waypoint, or click one to remove it.")
        }
        WAYPOINT_CLEAR_OP -> WAYPOINT_VARPS.indices.forEach { removeWaypoint(player, it) }
    }
}

fun placeWorldMapWaypoint(player: Player, tile: Tile): Boolean {
    if (player.vars.getVar(Varp.worldmap_waypoint_mode) != MODE_ON)
        return false
    val nearby = WAYPOINT_VARPS.firstOrNull { isNear(player.vars.getVar(it), tile) }
    if (nearby != null) {
        removeWaypoint(player, WAYPOINT_VARPS.indexOf(nearby))
        return true
    }
    val free = WAYPOINT_VARPS.firstOrNull { player.vars.getVar(it) == UNSET }
    if (free == null) {
        player.sendMessage("You have no waypoints left. Click one to remove it.")
        return true
    }
    setWaypointName(player, WAYPOINT_VARPS.indexOf(free), "")
    player.vars.saveVar(free, tile.id)
    return true
}

private fun isNear(stored: Int, tile: Tile): Boolean {
    if (stored == UNSET)
        return false
    val waypoint = Tile.of(stored)
    return waypoint.plane == tile.plane
        && abs(waypoint.x - tile.x) <= WAYPOINT_REMOVE_RADIUS_TILES
        && abs(waypoint.y - tile.y) <= WAYPOINT_REMOVE_RADIUS_TILES
}
