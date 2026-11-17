package org.darkan.core.city

data class Piece(val loc: Int, val shape: Int, val rotation: Int, val x: Int, val y: Int, val level: Int = 0)

data class Furnishing(val loc: String, val x: Int, val y: Int, val rotation: Int = 0, val level: Int? = null, val shape: Int? = null) {
    val plane get() = level ?: 0
}

data class Building(
    val kind: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val depth: Int,
    val door: String,
    val floors: Int,
    val wall: String,
    val floor: String,
    val furniture: List<Furnishing>,
    val roof: String? = null,
    val forSale: Boolean? = null,
) {
    val isForSale get() = forSale == true

    val maxX get() = x + width - 1
    val maxY get() = y + depth - 1
    val levels get() = floors.coerceIn(1, MAX_LEVELS)

    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int, margin: Int = 0) =
        maxX >= x - margin && minX <= this.maxX + margin && maxY >= y - margin && minY <= this.maxY + margin

    fun pieces(): List<Piece> = BuildingBuilder.pieces(this)

    companion object {
        const val MAX_LEVELS = 3
    }
}

data class Street(val x: Int, val y: Int, val width: Int, val depth: Int) {
    fun touches(minX: Int, minY: Int, maxX: Int, maxY: Int) = maxX >= x && minX < x + width && maxY >= y && minY < y + depth
}

data class Resident(val npc: Int, val x: Int, val y: Int, val role: String, val level: Int? = null, val shop: String? = null) {
    val plane get() = level ?: 0
}

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

    val homes get() = buildings.filter { it.isForSale }

    fun pieces(): List<Piece> = BuildingBuilder.pieces(this)
}
