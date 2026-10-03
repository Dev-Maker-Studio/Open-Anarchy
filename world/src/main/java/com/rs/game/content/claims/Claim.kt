package com.rs.game.content.claims

import world.gregs.voidps.type.Tile

data class Claim(
    var id: Int = 0,
    var owner: String = "",
    var plane: Int = 0,
    var minX: Int = 0,
    var minY: Int = 0,
    var maxX: Int = 0,
    var maxY: Int = 0,
    var allowConstruct: Boolean = false,
    var allowDestroy: Boolean = false,
    var allowPvp: Boolean = false
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val tileCount: Int get() = width * height

    fun contains(tile: Tile): Boolean = tile.plane == plane && tile.x in minX..maxX && tile.y in minY..maxY

    fun isEdge(tile: Tile): Boolean = contains(tile) && (tile.x == minX || tile.x == maxX || tile.y == minY || tile.y == maxY)

    fun overlaps(other: Claim): Boolean =
        plane == other.plane && minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY

    fun nearestCorner(tile: Tile): Pair<Int, Int> {
        val x = if (tile.x - minX <= maxX - tile.x) minX else maxX
        val y = if (tile.y - minY <= maxY - tile.y) minY else maxY
        return x to y
    }
}
