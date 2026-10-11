package com.rs.game.content.skills.sailing

import com.rs.game.World
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val GROUND_LEVEL = 0
private const val QUARTER_TURN = 90
private const val EIGHTH_TURN = 45
private const val ROTATIONS = 4
private const val ROTATION_NORTH = 1
private const val NO_TAG = -1
private val EXTRA_SLOTS = listOf(0 to -1, -2 to 1, -2 to -1, 2 to 1, 2 to -1, 3 to 0, 3 to 1, -3 to 0, -3 to 1, 3 to -1, -3 to -1, 4 to 0)

private class DeckPiece(val loc: Int, val forward: Int, val side: Int, val tag: Int = NO_TAG) {
    var obj: GameObject? = null
}

class BoatDeck {
    private val pieces = mutableListOf<DeckPiece>()

    fun build(boat: Boat) {
        clear()
        pieces.add(DeckPiece(Loc.sailing_helm, HELM_FORWARD, 0))
        pieces.add(DeckPiece(Loc.sailing_cargo_hold, 0, HOLD_SIDE))
        if (boat.cannon != null) pieces.add(DeckPiece(Loc.sailing_cannon, CANNON_FORWARD, CANNON_SIDE))
        if (boat.hook != null) pieces.add(DeckPiece(Loc.sailing_salvaging_hook, HOOK_FORWARD, -HOOK_SIDE))
        if (Facility.ANCHOR in boat.facilities) pieces.add(DeckPiece(Loc.sailing_anchor, ANCHOR_FORWARD, 0))
        if (boat.hasCatcher) pieces.add(DeckPiece(Loc.sailing_wind_catcher, HELM_FORWARD, -HOLD_SIDE))
        val extras = EXTRA_SLOTS.iterator()
        fun extra(loc: Int, tag: Int = NO_TAG) {
            if (extras.hasNext()) extras.next().let { (forward, side) -> pieces.add(DeckPiece(loc, forward, side, tag)) }
        }
        boat.nets.indices.forEach { extra(Loc.sailing_trawling_net, it) }
        Facility.entries.filter { it in boat.facilities }.forEach { facility ->
            when (facility) {
                Facility.KEG -> extra(Loc.sailing_keg)
                Facility.SALVAGING_STATION -> extra(Loc.sailing_salvaging_station)
                Facility.CRYSTAL_EXTRACTOR -> extra(Loc.sailing_crystal_extractor)
                Facility.TELEPORT_FOCUS -> extra(Loc.placeable_teleport_focus_built)
                Facility.GREATER_TELEPORT_FOCUS -> extra(Loc.placeable_greater_focus_built)
                else -> Unit
            }
        }
    }

    fun netAt(x: Int, y: Int): Int? = pieces.firstOrNull { it.tag != NO_TAG && it.obj?.tile?.x == x && it.obj?.tile?.y == y }?.tag

    fun update(centreX: Int, centreY: Int, heading: Int) {
        val radians = Math.toRadians(heading.toDouble())
        val forwardX = sin(radians)
        val forwardY = cos(radians)
        val rotation = (Math.floorMod((heading + EIGHTH_TURN) / QUARTER_TURN, ROTATIONS) + ROTATION_NORTH) % ROTATIONS
        pieces.forEach { piece ->
            val x = centreX + (piece.forward * forwardX + piece.side * forwardY).roundToInt()
            val y = centreY + (piece.forward * forwardY - piece.side * forwardX).roundToInt()
            val current = piece.obj
            if (current != null && current.tile.x == x && current.tile.y == y && current.rotation == rotation) return@forEach
            current?.let { World.removeObject(it) }
            val next = GameObject(piece.loc, ObjectShape.SCENERY_INTERACT, rotation, Tile.of(x, y, GROUND_LEVEL))
            World.spawnObject(next, false)
            piece.obj = next
        }
    }

    fun clear() {
        pieces.forEach { piece -> piece.obj?.let { World.removeObject(it) } }
        pieces.clear()
    }

    private companion object {
        const val HELM_FORWARD = -1
        const val HOLD_SIDE = 1
        const val CANNON_FORWARD = 1
        const val CANNON_SIDE = 1
        const val HOOK_FORWARD = 1
        const val HOOK_SIDE = 1
        const val ANCHOR_FORWARD = 2
    }
}
