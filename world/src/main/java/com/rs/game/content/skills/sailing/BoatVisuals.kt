package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.npc.NPC
import world.gregs.voidps.type.Tile
import kotlin.math.cos
import kotlin.math.sin

private const val GROUND_LEVEL = 0
private const val FACE_DISTANCE = 12

object BoatVisuals {
    fun fits(boat: Boat, x: Int, y: Int, heading: Int): Boolean = boat.footprintClear(x, y, heading, PortSites::sailable)

    fun spawn(boat: Boat, x: Int, y: Int, heading: Int): NPC {
        val npc = NPC(boat.type.visualNpc, corner(boat.type, x, y), null, true)
        npc.setRandomWalk(false)
        npc.setPermName("${boat.name} (${boat.type.name.lowercase()})")
        face(npc, x, y, heading)
        return npc
    }

    fun move(npc: NPC, boat: Boat, x: Int, y: Int, heading: Int) {
        val target = corner(boat.type, x, y)
        if (npc.tile != target) npc.tele(target)
        face(npc, x, y, heading)
    }

    private fun corner(type: BoatType, x: Int, y: Int): Tile {
        val offset = (type.visualSize - 1) / 2
        return Tile.of(x - offset, y - offset, GROUND_LEVEL)
    }

    private fun face(npc: NPC, x: Int, y: Int, heading: Int) {
        val radians = Math.toRadians(heading.toDouble())
        npc.setNextFaceTile(Tile.of(x + (sin(radians) * FACE_DISTANCE).toInt(), y + (cos(radians) * FACE_DISTANCE).toInt(), GROUND_LEVEL))
    }
}
