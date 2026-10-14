package com.rs.game.content.world.generated

import com.rs.game.map.Chunk
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SEED = 1L
private const val BUILDINGS = 6
private const val ATTEMPTS = 24
private const val MIN_RADIUS = 11
private const val RADIUS_SPREAD = 9
private const val CLEAR_RADIUS = 5
private const val MARGIN = 1
private const val CHUNK_SHIFT = 3
private const val MIN_SIDE = 5
private const val SIDE_SPREAD = 4
private const val GROUND = 0
private const val WALL = 5240
private const val WINDOW = 5241
private const val DOOR = 1530
private const val TABLE = 602
private const val CHAIR = 1088
private const val BED = 417
private const val BARREL = 362
private const val CRATE = 366
private const val BOOKCASE = 380

private enum class Side(val rotation: Int) { WEST(0), NORTH(1), EAST(2), SOUTH(3) }

private class Piece(val id: Int, val shape: ObjectShape, val rotation: Int, val x: Int, val y: Int)

private class Building(val x: Int, val y: Int, val width: Int, val depth: Int, val door: Side) {
    val maxX get() = x + width - 1
    val maxY get() = y + depth - 1

    fun covers(px: Int, py: Int, margin: Int) = px in x - margin..maxX + margin && py in y - margin..maxY + margin

    fun overlaps(other: Building, margin: Int) =
        x - margin <= other.maxX + margin && other.x - margin <= maxX + margin &&
            y - margin <= other.maxY + margin && other.y - margin <= maxY + margin

    val pieces: List<Piece> by lazy { walls() + furniture() }

    private fun doorTile(): Pair<Int, Int> = when (door) {
        Side.WEST -> x to y + depth / 2
        Side.EAST -> maxX to y + depth / 2
        Side.SOUTH -> x + width / 2 to y
        Side.NORTH -> x + width / 2 to maxY
    }

    private fun inside(): Pair<Int, Int> = doorTile().let { (dx, dy) ->
        when (door) {
            Side.WEST -> dx + 1 to dy
            Side.EAST -> dx - 1 to dy
            Side.SOUTH -> dx to dy + 1
            Side.NORTH -> dx to dy - 1
        }
    }

    private fun walls(): List<Piece> {
        val pieces = ArrayList<Piece>()
        val entrance = doorTile()
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
                pieces += Piece(WALL, ObjectShape.WALL_WHOLE_CORNER, rotation, px, py)
                continue
            }
            val side = when {
                west -> Side.WEST
                east -> Side.EAST
                south -> Side.SOUTH
                else -> Side.NORTH
            }
            val id = when {
                px to py == entrance -> DOOR
                side != door && (px + py) % 2 == 1 -> WINDOW
                else -> WALL
            }
            pieces += Piece(id, ObjectShape.WALL_STRAIGHT, side.rotation, px, py)
        }
        return pieces
    }

    private fun furniture(): List<Piece> {
        val pieces = ArrayList<Piece>()
        val entry = inside()
        val taken = HashSet<Pair<Int, Int>>()
        fun place(id: Int, px: Int, py: Int, rotation: Int, extra: Int = 0) {
            val tiles = (0..extra).map { px to py + it }
            if (tiles.any { (tx, ty) -> tx !in x + 1..maxX - 1 || ty !in y + 1..maxY - 1 || tx to ty in taken || abs(tx - entry.first) + abs(ty - entry.second) <= 1 }) return
            taken += tiles
            pieces += Piece(id, ObjectShape.SCENERY_INTERACT, rotation, px, py)
        }
        val midX = x + width / 2
        val midY = y + depth / 2
        place(TABLE, midX, midY, 0)
        place(CHAIR, midX - 1, midY, 1)
        place(CHAIR, midX + 1, midY, 3)
        place(BED, x + 1, maxY - 2, 0, 1)
        place(BOOKCASE, maxX - 1, maxY - 2, 2, 1)
        place(BARREL, x + 1, y + 1, 0)
        place(CRATE, maxX - 1, y + 1, 0)
        return pieces
    }
}

object Town {
    private val buildings: List<Building> by lazy { layout() }

    private fun anchor() = StartLodestone.tile

    private fun layout(): List<Building> {
        if (!EnvVars.generateMaps) return emptyList()
        val random = Random(SEED)
        val placed = ArrayList<Building>()
        val anchor = anchor()
        for (index in 0 until BUILDINGS) {
            val base = Math.PI * 2 * index / BUILDINGS
            repeat(ATTEMPTS) {
                if (placed.size > index) return@repeat
                val angle = base + (random.nextDouble() - 0.5) * 0.6
                val radius = MIN_RADIUS + random.nextInt(RADIUS_SPREAD)
                val width = MIN_SIDE + random.nextInt(SIDE_SPREAD)
                val depth = MIN_SIDE + random.nextInt(SIDE_SPREAD)
                val cx = anchor.x + (cos(angle) * radius).roundToInt()
                val cy = anchor.y + (sin(angle) * radius).roundToInt()
                val door = when {
                    abs(anchor.x - cx) > abs(anchor.y - cy) -> if (anchor.x < cx) Side.WEST else Side.EAST
                    else -> if (anchor.y < cy) Side.SOUTH else Side.NORTH
                }
                val candidate = Building(cx - width / 2, cy - depth / 2, width, depth, door)
                if (isClear(candidate) && placed.none { it.overlaps(candidate, MARGIN) }) placed += candidate
            }
        }
        Logger.info(Town::class.java, "layout", "Town has ${placed.size} buildings: ${placed.joinToString { "${it.x},${it.y} ${it.width}x${it.depth} door ${it.door}" }}")
        return placed
    }

    private fun isClear(building: Building): Boolean {
        val anchor = anchor()
        val minX = building.x - MARGIN
        val minY = building.y - MARGIN
        val maxX = building.maxX + MARGIN
        val maxY = building.maxY + MARGIN
        if (abs(anchor.x - (building.x + building.width / 2)) < CLEAR_RADIUS + building.width / 2 &&
            abs(anchor.y - (building.y + building.depth / 2)) < CLEAR_RADIUS + building.depth / 2
        ) return false
        if (GeneratedWorld.roadTouches(minX, minY, maxX, maxY) || GeneratedWorld.lodestoneZoneTouches(minX, minY, maxX, maxY)) return false
        val generator = GeneratedWorld.generator
        for (px in minX..maxX) for (py in minY..maxY) if (generator.liquidTileAt(px, py) != null) return false
        return true
    }

    @JvmStatic
    fun covers(x: Int, y: Int): Boolean = buildings.any { it.covers(x, y, MARGIN) }

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND) return
        for (building in buildings) for (piece in building.pieces) {
            if (piece.x shr CHUNK_SHIFT != chunk.x || piece.y shr CHUNK_SHIFT != chunk.y) continue
            chunk.addGeneratedObject(GameObject(piece.id, piece.shape, piece.rotation, piece.x, piece.y, GROUND))
        }
    }
}
