package world.gregs.voidps.type

@Suppress("NOTHING_TO_INLINE")
object Tiles {


    @JvmStatic
    inline fun of(x: Int, y: Int, level: Int): Any = Tile(x, y, level)

    @JvmStatic
    inline fun of(x: Int, y: Int): Any = Tile(x, y, 0)

    @JvmStatic
    inline fun ofId(id: Int): Any = Tile(id)

    @JvmStatic
    fun copy(tile: Any): Any {
        val t = tile as Tile
        return Tile(t.id)
    }

    @JvmStatic
    fun ofRandom(tile: Any, randomize: Int): Any {
        return Tile.of(tile as Tile, randomize)
    }


    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int, dl: Int): Any {
        return (tile as Tile).add(dx, dy, dl)
    }

    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int): Any {
        return (tile as Tile).add(dx, dy, 0)
    }


    @JvmStatic
    fun getXInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getXInScene(baseChunkId)

    @JvmStatic
    fun getYInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getYInScene(baseChunkId)

    @JvmStatic
    fun getChunkXInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getChunkXInScene(baseChunkId)

    @JvmStatic
    fun getChunkYInScene(tile: Any, baseChunkId: Int): Int = (tile as Tile).getChunkYInScene(baseChunkId)

    @JvmStatic
    fun getChunkX(tile: Any): Int = (tile as Tile).x shr 3

    @JvmStatic
    fun getChunkY(tile: Any): Int = (tile as Tile).y shr 3

    @JvmStatic
    fun isAt(tile: Any, x: Int, y: Int): Boolean {
        val t = tile as Tile
        return t.x == x && t.y == y
    }
}
