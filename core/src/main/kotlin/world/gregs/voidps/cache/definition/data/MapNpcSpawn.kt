package world.gregs.voidps.cache.definition.data

class MapNpcSpawn(
    val npc: Int,
    val x: Int,
    val y: Int,
    val level: Int
) {

    fun packed(): Int = (level shl 14) or (x shl 7) or y

    override fun toString(): String = "MapNpcSpawn(npc=$npc, x=$x, y=$y, level=$level)"

    companion object {

        fun of(packed: Int, npc: Int): MapNpcSpawn = MapNpcSpawn(
            npc = npc,
            x = (packed shr 7) and 0x3f,
            y = packed and 0x3f,
            level = packed shr 14
        )

        const val RECORD_SIZE = 4
    }
}
