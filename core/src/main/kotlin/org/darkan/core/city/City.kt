package org.darkan.core.city

data class Piece(val loc: Int, val shape: Int, val rotation: Int, val x: Int, val y: Int)

data class Building(val kind: String, val x: Int, val y: Int, val width: Int, val depth: Int, val door: String, val pieces: List<Piece>) {
    val maxX get() = x + width - 1
    val maxY get() = y + depth - 1

    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int, margin: Int = 0) =
        maxX >= x - margin && minX <= this.maxX + margin && maxY >= y - margin && minY <= this.maxY + margin
}

data class Street(val x: Int, val y: Int, val width: Int, val depth: Int) {
    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int) = maxX >= x && minX < x + width && maxY >= y && minY < y + depth
}

data class Resident(val npc: Int, val x: Int, val y: Int, val role: String)

data class City(
    val id: String,
    val name: String,
    val seed: Long,
    val centerX: Int,
    val centerY: Int,
    val halfSize: Int,
    val baseHeight: Int,
    val streets: List<Street>,
    val buildings: List<Building>,
    val residents: List<Resident>,
) {
    fun covers(x: Int, y: Int, margin: Int = 0) = touches(x, y, x, y, margin)

    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int, margin: Int = 0) =
        maxX >= centerX - halfSize - margin && minX <= centerX + halfSize + margin &&
            maxY >= centerY - halfSize - margin && minY <= centerY + halfSize + margin

    fun streetTouches(minX: Int, minY: Int, maxX: Int, maxY: Int) =
        touches(minX, minY, maxX, maxY) && streets.any { it.touches(minX, minY, maxX, maxY) }

    fun buildingAt(x: Int, y: Int) = buildings.firstOrNull { it.touches(x, y, x, y) }
}
