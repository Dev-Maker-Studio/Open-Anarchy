package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile

object MapTileDecoder {

    private val indices = IntArray(MapDefinition.LEVELS * MapDefinition.SIZE * MapDefinition.SIZE)

    init {
        var i = 0
        for (level in 0 until MapDefinition.LEVELS) {
            for (localX in 0 until MapDefinition.SIZE) {
                for (localY in 0 until MapDefinition.SIZE) {
                    indices[i++] = MapDefinition.index(localX, localY, level)
                }
            }
        }
    }

    fun decode(data: ByteArray, id: Int = -1): MapDefinition {
        val definition = MapDefinition(id)
        decode(data, definition)
        return definition
    }

    fun decodeArchive(data: ByteArray, id: Int = -1, levels: Int = MapDefinition.LEVELS): MapDefinition {
        val definition = MapDefinition(id, levels = levels)
        val end = decode(data, definition, levels)
        definition.effects.addAll(MapEffectDecoder.decode(data, end, id))
        return definition
    }

    fun decode(data: ByteArray, definition: MapDefinition, levels: Int = MapDefinition.LEVELS): Int {
        require(levels in 0..MapDefinition.LEVELS) { "A map square has 0 to ${MapDefinition.LEVELS} levels, not $levels." }
        definition.levels = levels
        var position = 0
        val size = data.size
        val tiles = levels * MapDefinition.SIZE * MapDefinition.SIZE
        for (tile in 0 until tiles) {
            val index = indices[tile]
            var height = 0
            var attrOpcode = 0
            var overlay = 0
            var path = 0
            var rotation = 0
            var settings = 0
            var underlay = 0
            while (position < size) {
                val opcode = data[position++].toInt() and 0xff
                if (opcode == 0) {
                    break
                }
                if (opcode == 1) {
                    height = data[position++].toInt() and 0xff
                    break
                }
                when {
                    opcode <= 49 -> {
                        attrOpcode = opcode
                        overlay = data[position++].toInt() and 0xff
                        path = (opcode - 2) / 4
                        rotation = (opcode - 2) and 3
                    }
                    opcode <= 81 -> settings = opcode - 49
                    else -> underlay = opcode - 81
                }
            }
            definition.tiles[index] = MapTile.pack(height, attrOpcode, overlay, path, rotation, settings, underlay)
        }
        return position
    }

    fun loadTiles(data: ByteArray, settings: ByteArray) {
        var position = 0
        val size = data.size
        for (index in indices) {
            var setting = 0
            while (position < size) {
                val config = data[position++].toInt() and 0xff
                when {
                    config == 0 -> {
                        settings[index] = setting.toByte()
                        break
                    }
                    config == 1 -> {
                        position++
                        settings[index] = setting.toByte()
                        break
                    }
                    config <= 49 -> position++
                    config <= 81 -> setting = config - 49
                }
            }
        }
    }
}
