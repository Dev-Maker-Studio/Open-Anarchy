package org.darkan.core.dungeon

import org.darkan.core.city.Piece
import org.darkan.core.city.Side
import org.darkan.core.gameval.gen.Loc
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object DungeonGenerator {
    const val MIN_ROOMS = 5
    const val MAX_ROOMS = 9
    private const val SIZE = DungeonGrid.SIZE
    private const val CELLS = 4
    private const val CELL = 30
    private const val MARGIN = 4
    private const val CELL_GAP = 1
    private const val ENTRY_SIZE = 9
    private const val BOSS_SIZE = 21
    private const val HALL_MIN = 9
    private const val HALL_STEPS = 5
    private const val SIDE_STEPS = 3
    private const val ODD_STEP = 2
    private const val MAX_SIDE_ROOMS = 3
    private const val ORGANIC_WOBBLE = 0.35
    private const val EXIT_OFFSET = 2
    private const val ARENA_CORNER = 6
    private const val ARENA_RADIUS = 8
    private const val SPAWN_AREA = 36
    private const val MIN_SPAWNS = 2
    private const val MAX_SPAWNS = 6
    private const val SPAWN_SPACING = 3
    private const val SPAWN_ATTEMPTS = 60
    private const val MIN_DECOR = 2
    private const val DECOR_SPREAD = 3
    private const val DECOR_ATTEMPTS = 40
    private const val LANE = 2
    private const val UNREACHED = -1
    private const val PORTAL = Loc.rd_portal_room2_exit

    private val layouts = ConcurrentHashMap<Dungeon, DungeonLayout>()

    fun layout(dungeon: Dungeon): DungeonLayout = layouts.computeIfAbsent(dungeon, ::generate)

    private class Build(val theme: DungeonTheme, val random: Random) {
        val floor = BooleanArray(SIZE * SIZE)
        val arena = BooleanArray(SIZE * SIZE)
        val channel = BooleanArray(SIZE * SIZE)
        val taken = HashSet<Int>()
        val pieces = ArrayList<Piece>()

        fun open(x: Int, y: Int) = x in 0 until SIZE && y in 0 until SIZE && floor[DungeonLayout.index(x, y)]

        fun carve(x: Int, y: Int) {
            if (x in MARGIN until SIZE - MARGIN && y in MARGIN until SIZE - MARGIN) floor[DungeonLayout.index(x, y)] = true
        }

        fun place(loc: Int, x: Int, y: Int, shape: Int = DungeonLayout.SCENERY_SHAPE, rotation: Int = 0) {
            pieces += Piece(loc, shape, rotation, x, y)
            if (shape == DungeonLayout.SCENERY_SHAPE) taken += DungeonLayout.index(x, y)
        }

        fun crowded(x: Int, y: Int, reach: Int = 1): Boolean {
            for (dx in -reach..reach) for (dy in -reach..reach) if (DungeonLayout.index(x + dx, y + dy) in taken) return true
            return false
        }
    }

    private fun generate(dungeon: Dungeon): DungeonLayout {
        val random = Random(dungeon.seed)
        val build = Build(dungeon.style, random)
        val length = dungeon.rooms?.coerceIn(MIN_ROOMS, MAX_ROOMS) ?: (MIN_ROOMS + random.nextInt(MAX_ROOMS - MIN_ROOMS + 1))
        val path = walk(random, length)
        val rooms = ArrayList<Room>()
        path.forEachIndexed { order, cell ->
            val kind = when (order) {
                0 -> RoomKind.ENTRY
                path.lastIndex -> RoomKind.BOSS
                else -> RoomKind.HALL
            }
            rooms += room(random, cell, kind)
        }
        val links = (1 until rooms.size).map { rooms[it - 1] to rooms[it] }.toMutableList()
        val used = path.toMutableSet()
        repeat(1 + random.nextInt(MAX_SIDE_ROOMS)) {
            val options = (1 until path.lastIndex).flatMap { order -> neighbours(path[order]).filter { it !in used }.map { order to it } }
            if (options.isEmpty()) return@repeat
            val (order, cell) = options[random.nextInt(options.size)]
            used += cell
            val side = room(random, cell, RoomKind.SIDE)
            rooms += side
            links += rooms[order] to side
        }
        rooms.forEach { carveRoom(build, it) }
        links.forEach { (from, to) -> carveCorridor(build, from, to) }

        val entry = rooms.first()
        val boss = rooms[path.lastIndex]
        val exitX = entry.centerX - EXIT_OFFSET
        val exitY = entry.centerY + EXIT_OFFSET
        build.carve(exitX, exitY)
        build.carve(exitX, exitY - 1)
        build.place(build.theme.exit, exitX, exitY)
        val portalX = boss.centerX + ARENA_CORNER
        val portalY = boss.centerY + ARENA_CORNER
        build.place(PORTAL, portalX, portalY)
        for ((dx, dy) in listOf(-ARENA_CORNER to -ARENA_CORNER, ARENA_CORNER to -ARENA_CORNER, -ARENA_CORNER to ARENA_CORNER)) {
            build.place(build.theme.arenaDecor, boss.centerX + dx, boss.centerY + dy)
        }
        for (dx in -ARENA_RADIUS..ARENA_RADIUS) for (dy in -ARENA_RADIUS..ARENA_RADIUS) {
            if (dx * dx + dy * dy <= ARENA_RADIUS * ARENA_RADIUS && build.open(boss.centerX + dx, boss.centerY + dy)) {
                build.arena[DungeonLayout.index(boss.centerX + dx, boss.centerY + dy)] = true
            }
        }

        val distance = distances(build, exitX, exitY - 1)
        val furthest = distance[DungeonLayout.index(boss.centerX, boss.centerY)].coerceAtLeast(1)
        val spawns = ArrayList<SpawnPoint>()
        for (room in rooms) {
            if (room.kind == RoomKind.ENTRY || room.kind == RoomKind.BOSS) continue
            val wanted = (room.width * room.depth / SPAWN_AREA).coerceIn(MIN_SPAWNS, MAX_SPAWNS) + if (room.kind == RoomKind.SIDE) 1 else 0
            var placed = 0
            repeat(SPAWN_ATTEMPTS) {
                if (placed >= wanted) return@repeat
                val x = room.x + random.nextInt(room.width)
                val y = room.y + random.nextInt(room.depth)
                val reached = distance[DungeonLayout.index(x, y)]
                if (reached == UNREACHED || build.crowded(x, y)) return@repeat
                if (spawns.any { abs(it.x - x) < SPAWN_SPACING && abs(it.y - y) < SPAWN_SPACING }) return@repeat
                spawns += SpawnPoint(x, y, (reached.toDouble() / furthest).coerceIn(0.0, 1.0), false, random.nextLong())
                placed++
            }
        }
        spawns += SpawnPoint(boss.centerX, boss.centerY, 1.0, true, random.nextLong())
        rooms.forEach { decorate(build, it, spawns) }
        build.theme.wall?.let { walls(build, it) }
        for (index in build.channel.indices) if (build.arena[index] || index in build.taken) build.channel[index] = false
        return DungeonLayout(build.floor, build.arena, build.channel, rooms, exitX, exitY - 1, exitX, exitY, portalX, portalY, spawns, build.pieces)
    }

    private fun neighbours(cell: Int): List<Int> {
        val x = cell % CELLS
        val y = cell / CELLS
        return Side.entries.map { x + it.dx to y + it.dy }.filter { (cx, cy) -> cx in 0 until CELLS && cy in 0 until CELLS }.map { (cx, cy) -> cy * CELLS + cx }
    }

    private fun walk(random: Random, length: Int): List<Int> {
        val starts = shuffled((0 until CELLS * CELLS).toList(), random)
        for (start in starts) {
            val path = arrayListOf(start)
            if (extend(random, path, length)) return path
        }
        error("No dungeon path of $length rooms fits the grid")
    }

    private fun shuffled(cells: List<Int>, random: Random): List<Int> = cells.toMutableList().also { it.shuffle(random) }

    private fun extend(random: Random, path: ArrayList<Int>, length: Int): Boolean {
        if (path.size == length) return true
        for (next in shuffled(neighbours(path.last()), random)) {
            if (next in path) continue
            path += next
            if (extend(random, path, length)) return true
            path.removeAt(path.lastIndex)
        }
        return false
    }

    private fun room(random: Random, cell: Int, kind: RoomKind): Room {
        val width = dimension(random, kind)
        val depth = dimension(random, kind)
        val x = MARGIN + cell % CELLS * CELL + CELL_GAP + random.nextInt(CELL - 2 * CELL_GAP - width + 1)
        val y = MARGIN + cell / CELLS * CELL + CELL_GAP + random.nextInt(CELL - 2 * CELL_GAP - depth + 1)
        return Room(x, y, width, depth, kind)
    }

    private fun dimension(random: Random, kind: RoomKind): Int = when (kind) {
        RoomKind.ENTRY -> ENTRY_SIZE
        RoomKind.BOSS -> BOSS_SIZE
        RoomKind.HALL -> HALL_MIN + ODD_STEP * random.nextInt(HALL_STEPS)
        RoomKind.SIDE -> HALL_MIN + ODD_STEP * random.nextInt(SIDE_STEPS)
    }

    private fun carveRoom(build: Build, room: Room) {
        for (x in room.x until room.x + room.width) for (y in room.y until room.y + room.depth) {
            if (!build.theme.organic) {
                build.carve(x, y)
                continue
            }
            val nx = (x - room.centerX) / (room.width / 2.0)
            val ny = (y - room.centerY) / (room.depth / 2.0)
            if (nx * nx + ny * ny <= 1 + (build.random.nextDouble() - 0.5) * ORGANIC_WOBBLE) build.carve(x, y)
        }
    }

    private fun carveCorridor(build: Build, from: Room, to: Room) {
        val horizontalFirst = build.random.nextBoolean()
        val cornerX = if (horizontalFirst) to.centerX else from.centerX
        val cornerY = if (horizontalFirst) from.centerY else to.centerY
        carveLine(build, from.centerX, from.centerY, cornerX, cornerY)
        carveLine(build, cornerX, cornerY, to.centerX, to.centerY)
    }

    private fun carveLine(build: Build, fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val width = build.theme.corridor
        val low = -(width / 2)
        var x = fromX
        var y = fromY
        while (true) {
            val reach = if (build.theme.organic && build.random.nextInt(DECOR_SPREAD) == 0) 1 else 0
            for (dx in low - reach until low + width + reach) for (dy in low - reach until low + width + reach) build.carve(x + dx, y + dy)
            if (build.theme.channelOverlay != null) build.channel[DungeonLayout.index(x, y)] = true
            if (x == toX && y == toY) return
            x += Integer.signum(toX - x)
            y += Integer.signum(toY - y)
        }
    }

    private fun distances(build: Build, startX: Int, startY: Int): IntArray {
        val distance = IntArray(SIZE * SIZE) { UNREACHED }
        val queue = ArrayDeque<Int>()
        distance[DungeonLayout.index(startX, startY)] = 0
        queue += DungeonLayout.index(startX, startY)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val x = current / SIZE
            val y = current % SIZE
            for (side in Side.entries) {
                val nx = x + side.dx
                val ny = y + side.dy
                if (!build.open(nx, ny)) continue
                val next = DungeonLayout.index(nx, ny)
                if (distance[next] != UNREACHED || next in build.taken) continue
                distance[next] = distance[current] + 1
                queue += next
            }
        }
        return distance
    }

    private fun decorate(build: Build, room: Room, spawns: List<SpawnPoint>) {
        val decor = build.theme.decor
        val wanted = MIN_DECOR + build.random.nextInt(DECOR_SPREAD)
        var placed = 0
        repeat(DECOR_ATTEMPTS) {
            if (placed >= wanted) return@repeat
            val x = room.x + build.random.nextInt(room.width)
            val y = room.y + build.random.nextInt(room.depth)
            if (!build.open(x, y) || abs(x - room.centerX) <= LANE || abs(y - room.centerY) <= LANE) return@repeat
            if (Side.entries.none { !build.open(x + it.dx, y + it.dy) } || build.crowded(x, y)) return@repeat
            if (spawns.any { abs(it.x - x) <= 1 && abs(it.y - y) <= 1 }) return@repeat
            build.place(decor[build.random.nextInt(decor.size)], x, y, rotation = build.random.nextInt(Side.entries.size))
            placed++
        }
    }

    private fun walls(build: Build, loc: Int) {
        for (x in 0 until SIZE) for (y in 0 until SIZE) {
            if (!build.open(x, y)) continue
            val closed = Side.entries.filter { !build.open(x + it.dx, y + it.dy) }
            if (closed.isEmpty()) continue
            val corner = closed.firstOrNull { side -> closed.any { it.rotation == (side.rotation + 1) % Side.entries.size } }
            if (corner != null) build.place(loc, x, y, DungeonLayout.CORNER_SHAPE, corner.rotation)
            else build.place(loc, x, y, DungeonLayout.WALL_SHAPE, closed.first().rotation)
        }
    }
}
