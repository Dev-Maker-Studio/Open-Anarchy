package org.darkan.core.model

import world.gregs.voidps.type.Tile

/**
 * A deferred pathfinding event that routes an entity to a target each tick
 * until it reaches or determines unreachable.
 *
 * Concrete processing is handled by the movement system using the pathfinder.
 * This class simply captures the target and callbacks.
 */
class RouteEvent(
    val target: RouteTarget,
    val onReached: suspend () -> Unit,
    val onNearest: (suspend () -> Boolean)? = null,
)

sealed interface RouteTarget {
    data class ToTile(val tile: Tile) : RouteTarget
    data class ToEntity(val entityIndex: Int, val entitySize: Int, val isNpc: Boolean) : RouteTarget
    data class ToObject(val obj: WorldObject) : RouteTarget
    data class ToGroundItem(val groundItem: GroundItem) : RouteTarget
}
