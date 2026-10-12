package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.cache.definition.data.MapDefinition

abstract class MapObjectDecoder {

    fun decode(buffer: ByteArray, settings: ByteArray, regionTileX: Int, regionTileY: Int): Int {
        var position = 0
        var objectId = -1
        while (true) {
            var skip = 0
            var part: Int
            do {
                val peek = buffer[position++].toInt() and 0xff
                part = if (peek < 128) peek else (peek shl 8 or (buffer[position++].toInt() and 0xff)) - 32768
                skip += part
            } while (part == 32767)
            if (skip == 0) {
                break
            }
            objectId += skip
            var tile = 0
            while (true) {
                val loc = buffer[position++].toInt() and 0xff
                tile += when {
                    loc == 0 -> break
                    loc >= 128 -> (loc shl 8 or (buffer[position++].toInt() and 0xff)) - 32769
                    else -> loc - 1
                }

                val level = tile shr 12

                val data = buffer[position++].toInt()
                val shape = data shr 2
                val rotation = data and 0x3
                val localX = MapDefinition.localX(tile)
                val localY = MapDefinition.localY(tile)

                add(objectId, localX, localY, level, shape, rotation, regionTileX, regionTileY)
            }
        }
        return position
    }

    abstract fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int)
}
