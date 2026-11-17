package org.darkan.core.city

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.model.ObjectShape

enum class Side(val rotation: Int, val dx: Int, val dy: Int) {
    WEST(0, -1, 0), NORTH(1, 0, 1), EAST(2, 1, 0), SOUTH(3, 0, -1);

    val lateralX get() = -dy
    val lateralY get() = dx
}

object BuildingBuilder {
    const val BANK = "bank"
    const val SMITHY = "smithy"
    const val MARKET = "market"
    const val GUARDHOUSE = "guardhouse"
    const val HOUSE = "house"

    private const val WALL = Loc.ahoy_farmhouse_wall
    private const val WINDOW = Loc.ahoy_farmhouse_wall_window
    private const val DOOR = Loc.poordoor
    private const val ANVIL = Loc.loc_2783
    private const val FURNACE_WIDTH = 2
    private const val FURNACE_DEPTH = 3
    private const val ENTRY_CLEARANCE = 2

    class Result(val pieces: List<Piece>, val residents: List<Resident>)

    fun doorTile(x: Int, y: Int, width: Int, depth: Int, door: Side): Pair<Int, Int> = when (door) {
        Side.WEST -> x to y + depth / 2
        Side.EAST -> x + width - 1 to y + depth / 2
        Side.SOUTH -> x + width / 2 to y
        Side.NORTH -> x + width / 2 to y + depth - 1
    }

    fun build(kind: String, x: Int, y: Int, width: Int, depth: Int, door: Side): Result {
        val room = Room(kind, x, y, width, depth, door)
        return Result(room.walls() + room.furniture(), room.residents)
    }

    private class Room(val kind: String, val x: Int, val y: Int, val width: Int, val depth: Int, val door: Side) {
        val maxX = x + width - 1
        val maxY = y + depth - 1
        val entrance = doorTile(x, y, width, depth, door)
        val residents = ArrayList<Resident>()
        private val taken = HashSet<Pair<Int, Int>>()
        private val pieces = ArrayList<Piece>()
        private val lateral = if (door == Side.NORTH || door == Side.SOUTH) width else depth
        private val inward = if (door == Side.NORTH || door == Side.SOUTH) depth else width

        fun walls(): List<Piece> {
            val result = ArrayList<Piece>()
            for (px in x..maxX) for (py in y..maxY) {
                val west = px == x
                val east = px == maxX
                val south = py == y
                val north = py == maxY
                if (!(west || east || south || north)) continue
                if ((west || east) && (south || north)) {
                    val rotation = when {
                        south && west -> Side.SOUTH.rotation
                        north && west -> Side.WEST.rotation
                        north && east -> Side.NORTH.rotation
                        else -> Side.EAST.rotation
                    }
                    result += Piece(WALL, ObjectShape.WALL_WHOLE_CORNER.id, rotation, px, py)
                    continue
                }
                val side = when {
                    west -> Side.WEST
                    east -> Side.EAST
                    south -> Side.SOUTH
                    else -> Side.NORTH
                }
                val loc = when {
                    px to py == entrance -> DOOR
                    side != door && (px + py) % 2 == 1 -> WINDOW
                    else -> WALL
                }
                result += Piece(loc, ObjectShape.WALL_STRAIGHT.id, side.rotation, px, py)
            }
            return result
        }

        fun furniture(): List<Piece> {
            when (kind) {
                BANK -> bank()
                SMITHY -> smithy()
                MARKET -> market()
                GUARDHOUSE -> guardhouse()
                else -> house()
            }
            return pieces
        }

        private fun at(across: Int, deep: Int) = entrance.first + door.lateralX * across - door.dx * deep to entrance.second + door.lateralY * across - door.dy * deep

        private fun free(tiles: List<Pair<Int, Int>>) = tiles.all { (tx, ty) ->
            tx in x + 1..maxX - 1 && ty in y + 1..maxY - 1 && tx to ty !in taken &&
                Math.abs(tx - entrance.first) + Math.abs(ty - entrance.second) > ENTRY_CLEARANCE - 1
        }

        private fun place(loc: Int, tile: Pair<Int, Int>, rotation: Int = door.rotation, footprint: List<Pair<Int, Int>> = listOf(tile)): Boolean {
            if (!free(footprint)) return false
            taken += footprint
            pieces += Piece(loc, ObjectShape.SCENERY_INTERACT.id, rotation, tile.first, tile.second)
            return true
        }

        private fun resident(npc: Int, tile: Pair<Int, Int>, role: String) {
            if (tile in taken || tile.first !in x + 1..maxX - 1 || tile.second !in y + 1..maxY - 1) return
            residents += Resident(npc, tile.first, tile.second, role)
        }

        private fun house() {
            place(Loc.table, at(0, inward / 2))
            place(Loc.chair, at(-1, inward / 2), Side.NORTH.rotation)
            place(Loc.chair, at(1, inward / 2), Side.SOUTH.rotation)
            place(Loc.bed, at(-lateral / 2 + 1, inward - 3))
            place(Loc.bookcase, at(lateral / 2 - 1, inward - 2))
            place(Loc.barrel, at(-lateral / 2 + 1, 1))
            place(Loc.crate, at(lateral / 2 - 1, 1))
        }

        private fun bank() {
            for (across in -1..1) {
                if (place(Loc.bankbooth, at(across, inward - 3))) resident(Npc.banker1, at(across, inward - 2), "banker")
            }
            place(Loc.barrel, at(-lateral / 2 + 1, 1))
            place(Loc.crate, at(lateral / 2 - 1, 1))
        }

        private fun smithy() {
            val footprint = { cx: Int, cy: Int -> (cx until cx + FURNACE_WIDTH).flatMap { fx -> (cy until cy + FURNACE_DEPTH).map { fy -> fx to fy } } }
            val corners = listOf(x + 1 to y + 1, maxX - FURNACE_WIDTH to y + 1, x + 1 to maxY - FURNACE_DEPTH, maxX - FURNACE_WIDTH to maxY - FURNACE_DEPTH)
            for (corner in corners) {
                if (!place(Loc.built_furnace, corner, Side.WEST.rotation, footprint(corner.first, corner.second))) continue
                val anvil = if (corner.first < x + width / 2) corner.first + FURNACE_WIDTH + 1 to corner.second + 1 else corner.first - 2 to corner.second + 1
                place(ANVIL, anvil)
                break
            }
            place(Loc.barrel, at(-lateral / 2 + 1, 1))
            place(Loc.crate, at(lateral / 2 - 1, 1))
        }

        private fun market() {
            for (across in -2..2 step 2) place(Loc.table, at(across, inward - 3))
            place(Loc.crate, at(-lateral / 2 + 1, inward - 2))
            place(Loc.barrel, at(lateral / 2 - 1, inward - 2))
            place(Loc.crate, at(lateral / 2 - 1, 1))
        }

        private fun guardhouse() {
            place(Loc.bench, at(0, inward - 2))
            place(Loc.barrel, at(-lateral / 2 + 1, 1))
            place(Loc.crate, at(lateral / 2 - 1, 1))
            resident(Npc.guard1, at(-1, inward / 2), "guard")
            resident(Npc.guard1, at(1, inward / 2), "guard")
        }
    }
}
