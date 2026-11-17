package org.darkan.core.dungeon

import org.darkan.core.city.Piece

data class Dungeon(
    val id: String,
    val name: String,
    val theme: String,
    val seed: Long,
    val slot: Int,
    val entranceX: Int,
    val entranceY: Int,
    val origin: String,
    val boss: String? = null,
    val rooms: Int? = null,
) {
    val style get() = DungeonTheme.of(theme)
    val baseX get() = DungeonGrid.baseX(slot)
    val baseY get() = DungeonGrid.baseY(slot)
    val layout get() = DungeonGenerator.layout(this)
    val bossId get() = boss ?: style.boss

    fun covers(x: Int, y: Int) = x >= baseX && x < baseX + DungeonGrid.SIZE && y >= baseY && y < baseY + DungeonGrid.SIZE

    fun entrancePiece() = Piece(style.entrance, DungeonLayout.SCENERY_SHAPE, 0, entranceX, entranceY)

    fun entranceTouches(x: Int, y: Int, margin: Int = 0) =
        x >= entranceX - margin && x < entranceX + style.entranceWidth + margin && y >= entranceY - margin && y < entranceY + style.entranceDepth + margin
}

object DungeonGrid {
    const val SIZE = 128
    const val REGION = 64
    const val REGIONS = SIZE / REGION
    private const val FIRST_REGION_X = 238
    private const val FIRST_REGION_Y = 2
    private const val PITCH = 3
    private const val COLUMNS = 5
    const val ORIGIN_X = FIRST_REGION_X * REGION

    fun baseX(slot: Int) = (FIRST_REGION_X + slot % COLUMNS * PITCH) * REGION

    fun baseY(slot: Int) = (FIRST_REGION_Y + slot / COLUMNS * PITCH) * REGION

    fun covers(x: Int) = x >= ORIGIN_X
}

enum class RoomKind { ENTRY, HALL, SIDE, BOSS }

class Room(val x: Int, val y: Int, val width: Int, val depth: Int, val kind: RoomKind) {
    val centerX get() = x + width / 2
    val centerY get() = y + depth / 2
}

class SpawnPoint(val x: Int, val y: Int, val progress: Double, val boss: Boolean, val roll: Long)

class DungeonLayout(
    val floor: BooleanArray,
    val arena: BooleanArray,
    val channel: BooleanArray,
    val rooms: List<Room>,
    val arrivalX: Int,
    val arrivalY: Int,
    val exitX: Int,
    val exitY: Int,
    val portalX: Int,
    val portalY: Int,
    val spawns: List<SpawnPoint>,
    val pieces: List<Piece>,
) {
    fun floorAt(x: Int, y: Int) = x in 0 until DungeonGrid.SIZE && y in 0 until DungeonGrid.SIZE && floor[index(x, y)]

    companion object {
        const val SCENERY_SHAPE = 10
        const val WALL_SHAPE = 0
        const val CORNER_SHAPE = 2

        fun index(x: Int, y: Int) = x * DungeonGrid.SIZE + y
    }
}
