package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import world.gregs.voidps.type.Region

object MapSquare {

    fun terrain(regionX: Int, regionY: Int): String = "m${regionX}_$regionY"

    fun locations(regionX: Int, regionY: Int): String = "l${regionX}_$regionY"

    fun npcs(regionX: Int, regionY: Int): String = "n${regionX}_$regionY"

    fun underwaterTerrain(regionX: Int, regionY: Int): String = "um${regionX}_$regionY"

    fun underwaterLocations(regionX: Int, regionY: Int): String = "ul${regionX}_$regionY"

    fun terrain(region: Region): String = terrain(region.x, region.y)

    fun locations(region: Region): String = locations(region.x, region.y)

    fun npcs(region: Region): String = npcs(region.x, region.y)

    fun underwaterTerrain(region: Region): String = underwaterTerrain(region.x, region.y)

    fun underwaterLocations(region: Region): String = underwaterLocations(region.x, region.y)

    enum class Shape(
        val prefix: String,
        val levels: Int,
        val encrypted: Boolean,
        val file: String
    ) {
        TERRAIN("m", MapSquare.LEVELS, false, "terrain"),
        LOCATIONS("l", 0, true, "locs"),
        NPCS("n", 0, true, "npcs"),
        UNDERWATER_TERRAIN("um", 1, false, "underwater_terrain"),
        UNDERWATER_LOCATIONS("ul", 0, false, "underwater_locs");

        fun name(regionX: Int, regionY: Int): String = "$prefix${regionX}_$regionY"

        companion object {

            fun ofFile(name: String): Shape? = entries.firstOrNull { it.file == name }
        }
    }

    data class Square(val shape: Shape, val regionX: Int, val regionY: Int) {

        val name: String
            get() = shape.name(regionX, regionY)

        val region: Int
            get() = (regionX shl 8) or regionY

        val directory: String
            get() = "${regionX}_$regionY"
    }

    fun of(nameHash: Int): Square? {
        val packed = hashes.get(nameHash)
        if (packed == 0) {
            return null
        }
        return Square(Shape.entries[(packed shr 16) - 1], (packed shr 8) and 0xff, packed and 0xff)
    }

    private const val REGIONS = 256

    private val hashes: Int2IntMap by lazy {
        val map = Int2IntOpenHashMap(Shape.entries.size * REGIONS * REGIONS)
        map.defaultReturnValue(0)
        for (shape in Shape.entries) {
            for (regionX in 0 until REGIONS) {
                for (regionY in 0 until REGIONS) {
                    val packed = ((shape.ordinal + 1) shl 16) or (regionX shl 8) or regionY
                    val previous = map.put(shape.name(regionX, regionY).hashCode(), packed)
                    check(previous == 0) {
                        "Map square names ${shape.name(regionX, regionY)} and " +
                            "${Shape.entries[(previous shr 16) - 1].name((previous shr 8) and 0xff, previous and 0xff)}" +
                            " share a name hash."
                    }
                }
            }
        }
        map
    }

    const val LEVELS = 4
}
