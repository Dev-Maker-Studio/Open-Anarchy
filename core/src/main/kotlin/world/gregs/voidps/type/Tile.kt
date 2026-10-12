package world.gregs.voidps.type

import world.gregs.voidps.type.area.Cuboid
import java.security.SecureRandom

class Tile(val id: Int) : Coordinate3D<Tile> {

    @JvmOverloads
    constructor(x: Int, y: Int, level: Int = 0) : this(id(x, y, level))

    override val x: Int
        get() = x(id)
    override val y: Int
        get() = y(id)
    override val level: Int
        get() = level(id)

    val zone: Zone
        get() = Zone(x shr 3, y shr 3, level)
    val region: Region
        get() = Region(x shr 6, y shr 6)
    val regionLevel: RegionLevel
        get() = RegionLevel(x shr 6, y shr 6, level)

    fun getCoordFaceX(sizeX: Int, sizeY: Int, rotation: Int) = x + ((if (rotation == 1 || rotation == 3) sizeY else sizeX) - 1) / 2
    fun getCoordFaceX(sizeX: Int) = getCoordFaceX(sizeX, -1, -1)
    fun getCoordFaceY(sizeX: Int, sizeY: Int, rotation: Int) = y + ((if (rotation == 1 || rotation == 3) sizeX else sizeY) - 1) / 2
    fun getCoordFaceY(sizeY: Int) = getCoordFaceY(-1, sizeY, -1)

    override fun copy(x: Int, y: Int, level: Int) = Tile(x, y, level)

    fun distanceTo(other: Tile, width: Int, height: Int) = distanceTo(Distance.getNearest(other, width, height, this))

    fun distanceTo(other: Tile): Int {
        if (level != other.level) {
            return -1
        }
        return Distance.chebyshev(x, y, other.x, other.y)
    }

    fun within(other: Tile, radius: Int): Boolean {
        return Distance.within(x, y, level, other.x, other.y, other.level, radius)
    }

    fun within(x: Int, y: Int, level: Int, radius: Int): Boolean {
        return Distance.within(this.x, this.y, this.level, x, y, level, radius)
    }

    fun toCuboid(width: Int = 1, height: Int = 1) = Cuboid(this, width, height, 1)
    fun toCuboid(radius: Int) = Cuboid(minus(radius, radius), radius * 2 + 1, radius * 2 + 1, 1)


    fun transform(dx: Int, dy: Int, dl: Int): Tile = add(dx, dy, dl)

    fun transform(dx: Int, dy: Int): Tile = transform(dx, dy, 0)

    fun withinDistance(other: Tile, distance: Int) = within(other, distance)

    fun withinDistance(other: Tile) = withinDistance(other, 14)

    val plane: Int get() = level

    fun getRegionId() = region.id

    fun getChunkId() = zone.id

    fun getTileHash() = id

    fun getXInRegion() = x and 63

    fun getYInRegion() = y and 63

    fun getXInChunk() = x and 7

    fun getYInChunk() = y and 7

    fun getChunkLocalHash() = (x and 7) or ((y and 7) shl 4) or (level shl 8)

    fun matches(other: Tile) = id == other.id

    fun getChunkX() = x shr 3

    fun getChunkY() = y shr 3

    fun getXInScene(baseChunkId: Int): Int = (getChunkXInScene(baseChunkId) shl 3) + (x and 7)

    fun getYInScene(baseChunkId: Int): Int = (getChunkYInScene(baseChunkId) shl 3) + (y and 7)

    fun getChunkXInScene(baseChunkId: Int): Int = (x shr 3) - (baseChunkId shr 11 and 0x7ff)

    fun getChunkYInScene(baseChunkId: Int): Int = (y shr 3) - (baseChunkId and 0x7ff)

    fun getRegionX() = x shr 6

    fun getRegionY() = y shr 6

    fun getRegionHash() = getRegionY() or (getRegionX() shl 8) or (plane shl 16)

    fun getLongestDelta(other: Tile): Int {
        val dx = Math.abs(x - other.x)
        val dy = Math.abs(y - other.y)
        return maxOf(dx, dy)
    }

    fun localizeRegion(): Tile = Tile(x and 63, y and 63, level)

    fun withinArea(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        x >= x1 && x <= x2 && y >= y1 && y <= y2

    fun isAt(x: Int, y: Int) = this.x == x && this.y == y

    fun isAt(x: Int, y: Int, level: Int) = this.x == x && this.y == y && this.level == level

    override fun toString(): String {
        return "Tile($x, $y, $level)"
    }

    override fun hashCode(): Int = id

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tile) return false
        return id == other.id
    }

    companion object {
        @JvmStatic
        fun id(x: Int, y: Int, level: Int) = (y and 0x3fff) + ((x and 0x3fff) shl 14) + ((level and 0x3) shl 28)

        @JvmStatic
        fun id(x: Int, y: Int) = id(x, y, 0)

        @JvmStatic
        fun x(id: Int) = id shr 14 and 0x3fff

        @JvmStatic
        fun y(id: Int) = id and 0x3fff

        @JvmStatic
        fun level(id: Int) = id shr 28 and 0x3

        @JvmStatic
        val EMPTY = Tile(0)

        @JvmStatic
        fun of(x: Int, y: Int, level: Int) = Tile(x, y, level)

        @JvmStatic
        fun of(x: Int, y: Int) = of(x, y, 0)

        @JvmStatic
        fun of(plane: Int, regionX: Int, regionY: Int, localX: Int, localY: Int) =
            Tile(regionX * 64 + localX, regionY * 64 + localY, plane)

        @JvmStatic
        fun toInt(x: Int, y: Int, level: Int): Int = Tile(x, y, level).id

        @JvmStatic
        fun of(x: Int, y: Int, level: Int, @Suppress("UNUSED_PARAMETER") sub: Int) = Tile(x, y, level)

        @JvmStatic
        fun of(id: Int) = Tile(id)

        @JvmStatic
        fun of(tile: Tile) = Tile(tile.id)

        private val tileRandom = SecureRandom()

        @JvmStatic
        fun of(tile: Tile, randomize: Int): Tile {
            val dx = tileRandom.nextInt(randomize * 2 + 1) - randomize
            val dy = tileRandom.nextInt(randomize * 2 + 1) - randomize
            return Tile(tile.x + dx, tile.y + dy, tile.level)
        }

        fun fromMap(map: Map<String, Any>) = Tile(map["x"] as Int, map["y"] as Int, map["level"] as? Int ?: 0)
        fun fromArray(array: IntArray) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)
        fun fromArray(array: List<Int>) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)

        fun index(x: Int, y: Int): Int = (x and 0x7) or ((y and 0x7) shl 3)
        fun index(x: Int, y: Int, layer: Int): Int = index(x, y) or ((layer and 0x7) shl 6)
        fun indexX(index: Int) = index and 0x7
        fun indexY(index: Int) = index shr 3 and 0x7
        fun indexLayer(index: Int) = index shr 6 and 0x7
    }
}

fun Tile.equals(x: Int = this.x, y: Int = this.y, level: Int = this.level) = this.x == x && this.y == y && this.level == level
