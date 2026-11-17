package org.darkan.core.dungeon

object DungeonText {
    private const val VOID = ' '
    private const val FLOOR = '.'
    private const val CHANNEL = '~'
    private const val ARENA = ':'
    private const val EXIT = 'E'
    private const val ARRIVAL = 'a'
    private const val PORTAL = 'P'
    private const val BOSS = 'B'
    private const val DECOR = 'o'
    private const val DECILES = 9

    fun render(dungeon: Dungeon): String {
        val layout = dungeon.layout
        val size = DungeonGrid.SIZE
        val grid = Array(size) { y -> CharArray(size) { x ->
            val index = DungeonLayout.index(x, y)
            when {
                !layout.floor[index] -> VOID
                layout.arena[index] -> ARENA
                layout.channel[index] -> CHANNEL
                else -> FLOOR
            }
        } }
        layout.pieces.filter { it.shape == DungeonLayout.SCENERY_SHAPE }.forEach { grid[it.y][it.x] = DECOR }
        layout.spawns.forEach { grid[it.y][it.x] = if (it.boss) BOSS else '0' + (it.progress * DECILES).toInt() }
        grid[layout.exitY][layout.exitX] = EXIT
        grid[layout.arrivalY][layout.arrivalX] = ARRIVAL
        grid[layout.portalY][layout.portalX] = PORTAL
        val rows = grid.reversed().map { String(it).trimEnd() }
        val used = rows.indices.filter { rows[it].isNotEmpty() }
        val left = used.minOf { row -> rows[row].indexOfFirst { it != VOID } }
        return rows.subList(used.first(), used.last() + 1).joinToString("\n") { it.drop(left) }
    }

    fun summary(dungeon: Dungeon): String {
        val layout = dungeon.layout
        val mobs = layout.spawns.count { !it.boss }
        return "${dungeon.id}: ${dungeon.name}, ${dungeon.theme}, seed ${dungeon.seed}, entrance ${dungeon.entranceX},${dungeon.entranceY}, " +
            "base ${dungeon.baseX},${dungeon.baseY}, ${layout.rooms.size} rooms, $mobs mob respawn points, boss ${dungeon.bossId}"
    }
}
