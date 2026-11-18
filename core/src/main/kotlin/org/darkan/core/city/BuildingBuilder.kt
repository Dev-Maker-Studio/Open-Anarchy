package org.darkan.core.city

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.model.ObjectShape
import java.util.concurrent.ConcurrentHashMap

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

    const val LADDER = "ladder"
    const val LADDER_TOP = "laddertop"
    private const val BANK_BOOTH = "bankbooth"
    private const val BOOTH_TURN = 1 // a quarter turn from the door side lays the booths side by side as one counter of windows
    private const val TABLE = "table"
    private const val CHAIR = "chair"
    private const val BED = "bed"
    private const val BOOKCASE = "bookcase"
    private const val BARREL = "barrel"
    private const val CRATE = "crate"
    private const val BENCH = "bench"
    private const val FURNACE = "built_furnace"
    private const val ANVIL = "loc_2783"
    private const val FURNACE_WIDTH = 2
    private const val FURNACE_DEPTH = 3
    private const val ENTRY_CLEARANCE = 2
    private const val LADDER_STAGGER = 2
    private const val GROUND = 0
    private const val SHELVES = "shelves1"
    private const val BARBER_CHAIR = "fai_falador_barbers_chair"
    private const val BARBER_MIRROR = "fai_falador_barbers_mirror"
    const val BANKER = "banker"
    const val GUARD = "guard"
    const val PATROL = "patrol"
    const val SHOPKEEPER = "shopkeeper"
    const val BARBER = "barber"
    const val REALTOR = "realtor"
    const val ROOF_FLAT_ROTATION = 2
    private const val TOP_LEVEL = 3
    private const val EAVES = 1
    private val roofShapes = ObjectShape.STRAIGHT_SLOPE_ROOF.id..ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF.id
    private val cityPieces = ConcurrentHashMap<City, List<Piece>>()

    private val locIds = ConcurrentHashMap<String, Int>()
    private val built = ConcurrentHashMap<Building, List<Piece>>()

    class Plan(val furniture: List<Furnishing>, val residents: List<Resident>)

    fun locId(name: String): Int? {
        val id = locIds.computeIfAbsent(name) { runCatching { Loc::class.java.getField(it).apply { isAccessible = true }.getInt(null) }.getOrDefault(MISSING) }
        return id.takeIf { it != MISSING }
    }

    fun doorTile(x: Int, y: Int, width: Int, depth: Int, door: Side): Pair<Int, Int> = when (door) {
        Side.WEST -> x to y + depth / 2
        Side.EAST -> x + width - 1 to y + depth / 2
        Side.SOUTH -> x + width / 2 to y
        Side.NORTH -> x + width / 2 to y + depth - 1
    }

    fun pieces(building: Building): List<Piece> = built.computeIfAbsent(building) { walls(it) + furniture(it) + roof(it) + icons(it) }

    fun pieces(city: City): List<Piece> = cityPieces.computeIfAbsent(city) {
        val roofed = HashSet<Triple<Int, Int, Int>>()
        it.buildings.flatMap { building -> pieces(building) }.filter { piece -> piece.shape !in roofShapes || roofed.add(Triple(piece.x, piece.y, piece.level)) }
    }

    fun plan(kind: String, x: Int, y: Int, width: Int, depth: Int, door: Side, floors: Int, city: String = "", seed: Long = 0): Plan {
        val room = Room(kind, x, y, width, depth, door, floors, city, seed)
        room.furnish()
        return Plan(room.furniture, room.residents)
    }

    private fun furniture(building: Building): List<Piece> = building.furniture.mapNotNull { item ->
        val loc = locId(item.loc) ?: return@mapNotNull null
        Piece(loc, item.shape ?: ObjectShape.SCENERY_INTERACT.id, item.rotation, building.x + item.x, building.y + item.y, item.plane)
    }

    private fun icons(building: Building): List<Piece> {
        val icons = Service.of(building.kind)?.icons ?: return emptyList()
        val door = Side.valueOf(building.door)
        val centreX = building.x + building.width / 2
        val centreY = building.y + building.depth / 2
        return icons.mapIndexed { index, loc ->
            Piece(loc, ObjectShape.GROUND_DECORATION.id, GROUND, centreX + door.lateralX * index, centreY + door.lateralY * index, GROUND)
        }
    }

    fun roofRotation(west: Boolean, east: Boolean, south: Boolean, north: Boolean): Int {
        val corner = (west || east) && (south || north)
        return when {
            corner && south && west -> Side.NORTH.rotation
            corner && north && west -> Side.EAST.rotation
            corner && north && east -> Side.SOUTH.rotation
            corner -> Side.WEST.rotation
            west -> Side.EAST.rotation
            north -> Side.SOUTH.rotation
            east -> Side.WEST.rotation
            else -> Side.NORTH.rotation
        }
    }

    private fun roof(building: Building): List<Piece> {
        val style = RoofStyle.of(building.roof) ?: return emptyList()
        val level = building.levels
        if (level > TOP_LEVEL) return emptyList()
        val result = ArrayList<Piece>()
        for (px in building.x - EAVES..building.maxX + EAVES) for (py in building.y - EAVES..building.maxY + EAVES) {
            val ring = minOf(px - building.x, building.maxX - px, py - building.y, building.maxY - py)
            if (ring > 0) {
                result += Piece(style.loc, ObjectShape.STRAIGHT_FLAT_ROOF.id, ROOF_FLAT_ROTATION, px, py, level)
                continue
            }
            val west = px == building.x + ring
            val east = px == building.maxX - ring
            val south = py == building.y + ring
            val north = py == building.maxY - ring
            val corner = (west || east) && (south || north)
            val rotation = roofRotation(west, east, south, north)
            val shape = when {
                ring < 0 && corner -> ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF
                ring < 0 -> ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF
                corner -> ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF
                else -> ObjectShape.STRAIGHT_SLOPE_ROOF
            }
            result += Piece(style.loc, shape.id, rotation, px, py, level)
        }
        return result
    }

    private fun walls(building: Building): List<Piece> {
        val style = WallStyle.of(building.wall)
        val door = Side.valueOf(building.door)
        val entrance = doorTile(building.x, building.y, building.width, building.depth, door)
        val result = ArrayList<Piece>()
        for (level in 0 until building.levels) {
            val wall = if (level == GROUND) style.wall else style.upperWall
            val window = if (level == GROUND) style.window else style.upperWindow
            for (px in building.x..building.maxX) for (py in building.y..building.maxY) {
                val west = px == building.x
                val east = px == building.maxX
                val south = py == building.y
                val north = py == building.maxY
                if (!(west || east || south || north)) continue
                if ((west || east) && (south || north)) {
                    val rotation = when {
                        south && west -> Side.SOUTH.rotation
                        north && west -> Side.WEST.rotation
                        north && east -> Side.NORTH.rotation
                        else -> Side.EAST.rotation
                    }
                    result += Piece(wall, ObjectShape.WALL_WHOLE_CORNER.id, rotation, px, py, level)
                    continue
                }
                val side = when {
                    west -> Side.WEST
                    east -> Side.EAST
                    south -> Side.SOUTH
                    else -> Side.NORTH
                }
                val loc = when {
                    level == GROUND && px to py == entrance -> style.door
                    (level != GROUND || side != door) && (px + py) % 2 == 1 -> window
                    else -> wall
                }
                result += Piece(loc, ObjectShape.WALL_STRAIGHT.id, side.rotation, px, py, level)
            }
        }
        return result
    }

    private class Room(val kind: String, val x: Int, val y: Int, val width: Int, val depth: Int, val door: Side, val floors: Int, val city: String, val seed: Long) {
        val maxX = x + width - 1
        val maxY = y + depth - 1
        val entrance = doorTile(x, y, width, depth, door)
        val residents = ArrayList<Resident>()
        val furniture = ArrayList<Furnishing>()
        private val taken = HashSet<Triple<Int, Int, Int>>()
        private val lateral = if (door == Side.NORTH || door == Side.SOUTH) width else depth
        private val inward = if (door == Side.NORTH || door == Side.SOUTH) depth else width

        fun furnish() {
            ladders()
            when (kind) {
                BANK -> bank()
                SMITHY -> smithy()
                MARKET -> market()
                GUARDHOUSE -> guardhouse()
                Service.BARBER.kind -> barber()
                Service.REALTOR.kind -> realtor()
                else -> Service.of(kind)?.takeIf { it.keeper }?.let(::shop) ?: house()
            }
            for (level in 1 until floors) upstairs(level)
        }

        private fun at(across: Int, deep: Int) = entrance.first + door.lateralX * across - door.dx * deep to entrance.second + door.lateralY * across - door.dy * deep

        private fun free(tiles: List<Pair<Int, Int>>, level: Int) = tiles.all { (tx, ty) ->
            tx in x + 1..maxX - 1 && ty in y + 1..maxY - 1 && Triple(tx, ty, level) !in taken &&
                (level != GROUND || Math.abs(tx - entrance.first) + Math.abs(ty - entrance.second) > ENTRY_CLEARANCE - 1)
        }

        private fun place(loc: String, tile: Pair<Int, Int>, rotation: Int = door.rotation, footprint: List<Pair<Int, Int>> = listOf(tile), level: Int = GROUND): Boolean {
            if (!free(footprint, level)) return false
            footprint.forEach { taken += Triple(it.first, it.second, level) }
            furniture += Furnishing(loc, tile.first - x, tile.second - y, rotation, level.takeIf { it != GROUND })
            return true
        }

        private fun resident(npc: Int, tile: Pair<Int, Int>, role: String, shop: String? = null) {
            if (Triple(tile.first, tile.second, GROUND) in taken || tile.first !in x + 1..maxX - 1 || tile.second !in y + 1..maxY - 1) return
            taken += Triple(tile.first, tile.second, GROUND)
            residents += Resident(npc, tile.first, tile.second, role, shop = shop)
        }

        private fun shop(service: Service) {
            for (across in -1..1) place(TABLE, at(across, inward - 3))
            resident(CityPlanner.keeperNpc(seed, service.kind), at(0, inward - 2), SHOPKEEPER, if (city.isEmpty()) service.kind else "${city}_${service.kind}")
            place(SHELVES, at(-lateral / 2 + 1, inward - 2))
            place(SHELVES, at(lateral / 2 - 1, inward - 2))
            place(CRATE, at(-lateral / 2 + 1, 1))
            place(BARREL, at(lateral / 2 - 1, 1))
        }

        private fun barber() {
            for (across in -1..1 step 2) {
                place(BARBER_CHAIR, at(across, inward - 3))
                place(BARBER_MIRROR, at(across, inward - 2))
            }
            resident(Npc.npc_598, at(0, inward / 2), BARBER)
            place(BENCH, at(-lateral / 2 + 1, 1))
            place(CRATE, at(lateral / 2 - 1, 1))
        }

        private fun realtor() {
            place(TABLE, at(0, inward - 3))
            resident(Npc.city_realtor, at(0, inward - 2), REALTOR)
            place(BOOKCASE, at(-lateral / 2 + 1, inward - 2))
            place(BOOKCASE, at(lateral / 2 - 1, inward - 2))
            place(CHAIR, at(-1, 1), Side.NORTH.rotation)
            place(BENCH, at(lateral / 2 - 1, 1))
        }

        private fun ladders() {
            for (level in 0 until floors - 1) {
                val reach = lateral / 2 - 1 - level % 2 * LADDER_STAGGER
                val spot = listOf(reach to reach - 1, -reach to 1 - reach, reach - 1 to reach - 2, 1 - reach to 2 - reach)
                    .map { (ladder, beside) -> at(ladder, inward - 2) to at(beside, inward - 2) }
                    .firstOrNull { (foot, landing) -> (level..level + 1).all { free(listOf(foot, landing), it) } } ?: continue
                place(LADDER, spot.first, level = level)
                place(LADDER_TOP, spot.first, level = level + 1)
                taken += Triple(spot.second.first, spot.second.second, level)
                taken += Triple(spot.second.first, spot.second.second, level + 1)
            }
        }

        private fun upstairs(level: Int) {
            place(BED, at(-lateral / 2 + 1, inward - 3), level = level)
            place(TABLE, at(0, inward / 2), level = level)
            place(CHAIR, at(-1, inward / 2), Side.NORTH.rotation, level = level)
            place(BOOKCASE, at(-lateral / 2 + 1, 1), level = level)
            place(CRATE, at(lateral / 2 - 1, 1), level = level)
        }

        private fun house() {
            place(TABLE, at(0, inward / 2))
            place(CHAIR, at(-1, inward / 2), Side.NORTH.rotation)
            place(CHAIR, at(1, inward / 2), Side.SOUTH.rotation)
            if (floors < 2) place(BED, at(-lateral / 2 + 1, inward - 3))
            place(BOOKCASE, at(-lateral / 2 + 1, inward - 2))
            place(BARREL, at(-lateral / 2 + 1, 1))
            place(CRATE, at(lateral / 2 - 1, 1))
        }

        private fun bank() {
            for (across in -1..1) {
                if (place(BANK_BOOTH, at(across, inward - 3), (door.rotation + BOOTH_TURN) % 4)) resident(Npc.banker1, at(across, inward - 2), BANKER)
            }
            place(BARREL, at(-lateral / 2 + 1, 1))
            place(CRATE, at(lateral / 2 - 1, 1))
        }

        private fun smithy() {
            val footprint = { cx: Int, cy: Int -> (cx until cx + FURNACE_WIDTH).flatMap { fx -> (cy until cy + FURNACE_DEPTH).map { fy -> fx to fy } } }
            val corners = listOf(x + 1 to y + 1, maxX - FURNACE_WIDTH to y + 1, x + 1 to maxY - FURNACE_DEPTH, maxX - FURNACE_WIDTH to maxY - FURNACE_DEPTH)
            for (corner in corners) {
                if (!place(FURNACE, corner, Side.WEST.rotation, footprint(corner.first, corner.second))) continue
                val anvil = if (corner.first < x + width / 2) corner.first + FURNACE_WIDTH + 1 to corner.second + 1 else corner.first - 2 to corner.second + 1
                place(ANVIL, anvil)
                break
            }
            place(BARREL, at(-lateral / 2 + 1, 1))
            place(CRATE, at(lateral / 2 - 1, 1))
        }

        private fun market() {
            for (across in -2..2 step 2) place(TABLE, at(across, inward - 3))
            place(CRATE, at(-lateral / 2 + 1, inward - 2))
            place(BARREL, at(lateral / 2 - 1, inward - 2))
            place(CRATE, at(lateral / 2 - 1, 1))
        }

        private fun guardhouse() {
            place(BENCH, at(0, inward - 2))
            place(BARREL, at(-lateral / 2 + 1, 1))
            place(CRATE, at(lateral / 2 - 1, 1))
            resident(Npc.guard1, at(-1, inward / 2), GUARD)
            resident(Npc.guard1, at(1, inward / 2), GUARD)
        }
    }

    private const val MISSING = -1
}
