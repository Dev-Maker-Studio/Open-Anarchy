package com.rs.game.content.world

import com.rs.game.model.entity.player.Player
import org.darkan.core.gameval.gen.Component
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

val WORLD_MAP_WAYPOINT_BUTTON = Component.interface_755.component_755_0 + WAYPOINT_BUTTON_INDEX

private val WAYPOINT_VARPS = intArrayOf(
    Varp.worldmap_waypoint_0, Varp.worldmap_waypoint_1, Varp.worldmap_waypoint_2, Varp.worldmap_waypoint_3,
    Varp.worldmap_waypoint_4, Varp.worldmap_waypoint_5, Varp.worldmap_waypoint_6, Varp.worldmap_waypoint_7,
)

fun worldMapWaypointOption(player: Player, option: Int) {
    when (option) {
        WAYPOINT_ADD_OP -> {
            val adding = player.vars.getVar(Varp.worldmap_waypoint_mode) == MODE_ON
            player.vars.setVar(Varp.worldmap_waypoint_mode, if (adding) MODE_OFF else MODE_ON)
            if (!adding)
                player.sendMessage("Click the world map to place a waypoint, or click one to remove it.")
        }
        WAYPOINT_CLEAR_OP -> WAYPOINT_VARPS.forEach { player.vars.saveVar(it, UNSET) }
    }
}

fun placeWorldMapWaypoint(player: Player, tile: Tile): Boolean {
    if (player.vars.getVar(Varp.worldmap_waypoint_mode) != MODE_ON)
        return false
    val nearby = WAYPOINT_VARPS.firstOrNull { isNear(player.vars.getVar(it), tile) }
    if (nearby != null) {
        player.vars.saveVar(nearby, UNSET)
        return true
    }
    val free = WAYPOINT_VARPS.firstOrNull { player.vars.getVar(it) == UNSET }
    if (free == null) {
        player.sendMessage("You have no waypoints left. Click one to remove it.")
        return true
    }
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
