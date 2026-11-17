package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.map.ClipFlag
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import kotlin.math.abs

const val TOP_PLANE = 3
private const val GROUND_PLANE = 0
private const val RAISED_REACH = 4
private const val MAX_COLUMNS = 600
private const val SLOTS = 4
private const val SLOT_BITS = 2
private const val VIEW_KEY = "builtRoofView"
private const val EXAMINE_OP = 6
private const val EVERY_TICK = 1
private val ADJACENT = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
private val RAISED_OPTIONS = setOf(DESTROY_OPTION, PICK_UP_OPTION)

private class RoofView {
    var at = -1
    var version = -1
    var climbed = false
    var columns: Set<Int> = emptySet()
    var hidden: Map<Long, GameObject> = emptyMap()
}

object BuiltRoofs {
    @Volatile
    var version = 0
        private set

    fun pieceAt(tile: Tile, kind: PieceKind): GameObject? {
        val slot = if (kind == PieceKind.FLOOR) ObjectShape.GROUND_DECORATION.slot else ObjectShape.SCENERY_INTERACT.slot
        return World.getObjectWithSlot(tile, slot)?.takeIf { ConstructionCatalogue.forLoc(it.id)?.kind == kind }
    }

    fun hasFloor(tile: Tile): Boolean =
        pieceAt(tile, PieceKind.FLOOR) != null || ClipFlag.flagged(World.getClipFlags(tile.plane, tile.x, tile.y), ClipFlag.UNDER_ROOF)

    fun built(obj: GameObject, piece: ConstructionPiece, owner: String) {
        when (piece.kind) {
            PieceKind.ROOF -> reshapeAround(obj.tile)
            PieceKind.LADDER -> {
                val top = GameObject(piece.topLoc, obj.type, obj.rotation, obj.tile.transform(0, 0, 1))
                World.spawnObject(top)
                WorldDelta.setOwner(top, owner)
            }
            else -> {}
        }
        version++
    }

    fun removed(obj: GameObject, piece: ConstructionPiece) {
        when (piece.kind) {
            PieceKind.ROOF -> reshapeAround(obj.tile)
            PieceKind.LADDER -> World.getObjectWithId(obj.tile.transform(0, 0, 1), piece.topLoc)?.let(World::removeObject)
            else -> {}
        }
        version++
    }

    fun climb(player: Player, up: Boolean) {
        val destination = player.transform(0, 0, if (up) 1 else -1)
        if (destination.plane !in GROUND_PLANE..TOP_PLANE) return
        if (up && !hasFloor(destination)) {
            player.sendMessage("There is no floor up there to stand on. Use Place above on a floor piece beside the ladder first.")
            return
        }
        player.useStairs(Seq.human_reachforladder, destination)
    }

    fun handleRaisedClick(player: Player, tile: Tile, loc: Int, opNum: Int): Boolean {
        if (opNum == EXAMINE_OP || player.isLocked) return false
        val piece = ConstructionCatalogue.forLoc(loc)?.takeIf { it.kind == PieceKind.ROOF || it.kind == PieceKind.FLOOR } ?: return false
        val obj = (player.plane + 1..TOP_PLANE).firstNotNullOfOrNull { plane -> pieceAt(Tile.of(tile.x, tile.y, plane), piece.kind)?.takeIf { it.id == loc } } ?: return false
        val option = obj.definitions.getOption(opNum)?.takeIf { it in RAISED_OPTIONS } ?: return false
        if (maxOf(abs(player.x - obj.x), abs(player.y - obj.y)) > RAISED_REACH) {
            player.sendMessage("You can't reach that from here.")
            return true
        }
        if (!ClaimManager.allowObjectOption(player, obj, opNum)) return true
        player.stopAll(false)
        BuiltFurniture.handle(player, obj, option)
        return true
    }

    @JvmStatic
    fun hiddenFrom(player: Player, obj: GameObject): Boolean =
        player.tempAttribs.getO<RoofView>(VIEW_KEY)?.hidden?.containsKey(key(obj)) == true

    fun process(player: Player) {
        val view = view(player)
        if (!view.climbed && player.plane > GROUND_PLANE && pieceAt(player.tile, PieceKind.FLOOR) != null) view.climbed = true
        fall(player, view)
        val at = player.tile.getTileHash()
        if (view.at == at && view.version == version) return
        val changed = view.version != version
        val samePlane = view.at != -1 && Tile.of(view.at).plane == player.plane
        view.at = at
        if (!changed && samePlane && column(player.x, player.y) in view.columns) return
        view.version = version
        val columns = coveredColumns(player.tile)
        val hidden = hiddenPieces(columns, player.plane)
        for ((key, obj) in view.hidden) {
            if (key in hidden) continue
            World.getObjectWithSlot(obj.tile, obj.slot)?.takeIf { it.id == obj.id }?.let(player.packets::sendAddObject)
        }
        for ((key, obj) in hidden) if (changed || key !in view.hidden) player.packets.sendRemoveObject(obj)
        view.columns = columns
        view.hidden = hidden
    }

    private fun view(player: Player): RoofView =
        player.tempAttribs.getO<RoofView>(VIEW_KEY) ?: RoofView().also { player.tempAttribs.setO<RoofView>(VIEW_KEY, it) }

    private fun fall(player: Player, view: RoofView) {
        if (!view.climbed) return
        if (player.plane == GROUND_PLANE) {
            view.climbed = false
            return
        }
        if (player.isLocked || hasFloor(player.tile)) return
        player.stopAll()
        player.tele(player.transform(0, 0, -1))
        player.sendMessage("There is no floor here; you drop to the level below.")
    }

    private fun key(obj: GameObject): Long = (obj.tile.getTileHash().toLong() shl SLOT_BITS) or obj.slot.toLong()

    private fun column(x: Int, y: Int): Int = Tile.of(x, y, GROUND_PLANE).getTileHash()

    private fun covered(x: Int, y: Int, plane: Int): Boolean = (plane + 1..TOP_PLANE).any {
        val tile = Tile.of(x, y, it)
        pieceAt(tile, PieceKind.ROOF) != null || pieceAt(tile, PieceKind.FLOOR) != null
    }

    private fun coveredColumns(tile: Tile): Set<Int> {
        if (tile.plane >= TOP_PLANE || !covered(tile.x, tile.y, tile.plane)) return emptySet()
        val columns = HashSet<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        columns += column(tile.x, tile.y)
        queue += tile.x to tile.y
        while (queue.isNotEmpty() && columns.size < MAX_COLUMNS) {
            val (x, y) = queue.removeFirst()
            for ((dx, dy) in ADJACENT) {
                val next = column(x + dx, y + dy)
                if (next in columns || !covered(x + dx, y + dy, tile.plane)) continue
                columns += next
                queue += x + dx to y + dy
            }
        }
        return columns
    }

    private fun hiddenPieces(columns: Set<Int>, plane: Int): Map<Long, GameObject> {
        val hidden = HashMap<Long, GameObject>()
        for (column in columns) {
            val base = Tile.of(column)
            for (level in plane + 1..TOP_PLANE) for (slot in 0 until SLOTS) {
                val obj = World.getObjectWithSlot(Tile.of(base.x, base.y, level), slot) ?: continue
                if (ConstructionCatalogue.forLoc(obj.id) != null) hidden[key(obj)] = obj
            }
        }
        return hidden
    }

    private fun reshapeAround(tile: Tile) {
        reshape(tile)
        for ((dx, dy) in ADJACENT) reshape(tile.transform(dx, dy))
    }

    private fun reshape(tile: Tile) {
        val roof = pieceAt(tile, PieceKind.ROOF) ?: return
        val piece = ConstructionCatalogue.forLoc(roof.id) ?: return
        val west = pieceAt(tile.transform(-1, 0), PieceKind.ROOF) == null
        val east = pieceAt(tile.transform(1, 0), PieceKind.ROOF) == null
        val south = pieceAt(tile.transform(0, -1), PieceKind.ROOF) == null
        val north = pieceAt(tile.transform(0, 1), PieceKind.ROOF) == null
        val ridge = (west && east) || (south && north)
        val shape = when {
            ridge || !(west || east || south || north) -> ObjectShape.STRAIGHT_FLAT_ROOF
            (west || east) && (south || north) -> ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF
            else -> ObjectShape.STRAIGHT_SLOPE_ROOF
        }
        val rotation = if (shape == ObjectShape.STRAIGHT_FLAT_ROOF) BuildingBuilder.ROOF_FLAT_ROTATION else BuildingBuilder.roofRotation(west, east, south, north)
        if (roof.type == shape && roof.rotation == rotation) return
        val turned = GameObject(roof.id, shape, rotation, roof.tile)
        turned.attribs.setI(HP_ATTRIB, hitPoints(roof, piece))
        val owner = WorldDelta.ownerOf(roof)
        World.removeObject(roof)
        World.spawnObject(turned)
        WorldDelta.setOwner(turned, owner)
    }
}

@ServerStartupEvent
fun startBuiltRoofs() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(EVERY_TICK, EVERY_TICK) {
        World.players.forEach { player -> if (player.hasStarted() && !player.hasFinished()) BuiltRoofs.process(player) }
        true
    }
}
