package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.definition.data.MapNpcSpawn

object MapNpcDecoder {

    fun decode(data: ByteArray, region: Int = -1): MutableList<MapNpcSpawn> {
        require(data.size % MapNpcSpawn.RECORD_SIZE == 0) {
            "Npc spawns of region $region are ${data.size} bytes, not a whole number of " +
                "${MapNpcSpawn.RECORD_SIZE} byte records."
        }
        val buffer = BufferReader(data)
        val spawns = ArrayList<MapNpcSpawn>(data.size / MapNpcSpawn.RECORD_SIZE)
        while (buffer.remaining > 0) {
            spawns.add(MapNpcSpawn.of(buffer.readUnsignedShort(), buffer.readUnsignedShort()))
        }
        return spawns
    }
}
