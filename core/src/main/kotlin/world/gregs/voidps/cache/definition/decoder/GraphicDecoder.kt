package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.GRAPHICS
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic

class GraphicDecoder : DefinitionDecoder<GraphicDefinition>(GRAPHICS) {

    override fun size(cache: Cache): Int {
        return cache.lastArchiveId(index)
    }

    override fun create(size: Int) = Array(size) { GraphicDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: GraphicDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun GraphicDefinition.read(opcode: Int, buffer: Reader) {
        buffer.position(buffer.array().size - 2)
        val size: Int = buffer.readShort()
        val trailer = buffer.array().size - 7 - size * 8
        buffer.position(trailer)

        val offsetX: Int = buffer.readShort()
        val offsetY: Int = buffer.readShort()
        width = offsetX
        height = offsetY

        val paletteSize: Int = buffer.readUnsignedByte() + 1

        val graphics = Array(size) { IndexedGraphic() }
        this.graphics = graphics
        for (index in 0 until size) {
            graphics[index].offsetX = buffer.readShort()
        }
        for (index in 0 until size) {
            graphics[index].offsetY = buffer.readShort()
        }
        for (index in 0 until size) {
            graphics[index].width = buffer.readShort()
        }
        for (index in 0 until size) {
            graphics[index].height = buffer.readShort()
        }
        for (index in 0 until size) {
            val graphic = graphics[index]
            graphic.deltaWidth = offsetX - graphic.width - graphic.offsetX
            graphic.deltaHeight = offsetY - graphic.height - graphic.offsetY
        }

        val paletteStart = trailer - (paletteSize - 1) * 3
        buffer.position(paletteStart)
        val palette = IntArray(paletteSize)
        val raw = IntArray(paletteSize)
        for (index in 1 until paletteSize) {
            raw[index] = buffer.readUnsignedMedium()
            palette[index] = raw[index]
            if (palette[index] == 0) {
                palette[index] = 1
            }
        }
        rawPalette = raw
        for (index in 0 until size) {
            graphics[index].palette = palette
        }

        buffer.position(0)
        for (index in 0 until size) {
            val graphic = graphics[index]
            val area = graphic.width * graphic.height

            graphic.raster = ByteArray(area)

            val setting: Int = buffer.readUnsignedByte()
            graphic.flags = setting
            if (setting and 0x2 == 0) {
                if (setting and 0x1 == 0) {
                    for (pixel in 0 until area) {
                        graphic.raster[pixel] = buffer.readByte().toByte()
                    }
                } else {
                    for (x in 0 until graphic.width) {
                        for (y in 0 until graphic.height) {
                            graphic.raster[x + y * graphic.width] = buffer.readByte().toByte()
                        }
                    }
                }
            } else {
                var transparent = false
                val alpha = ByteArray(area)
                if (setting and 0x1 == 0) {
                    for (pixel in 0 until area) {
                        graphic.raster[pixel] = buffer.readByte().toByte()
                    }
                    for (pixel in 0 until area) {
                        alpha[pixel] = buffer.readByte().toByte()
                        val p = alpha[pixel].toInt()
                        transparent = transparent or (p != -1)
                    }
                } else {
                    for (x in 0 until graphic.width) {
                        for (y in 0 until graphic.height) {
                            graphic.raster[x + y * graphic.width] = buffer.readByte().toByte()
                        }
                    }
                    for (x in 0 until graphic.width) {
                        for (y in 0 until graphic.height) {
                            alpha[x + y * graphic.width] = buffer.readByte().toByte()
                            val pixel = alpha[x + y * graphic.width].toInt()
                            transparent = transparent or (pixel != -1)
                        }
                    }
                }
                if (transparent) {
                    graphic.alpha = alpha
                }
            }
        }

        val padding = paletteStart - buffer.position()
        paletteZeroEntry = when (padding) {
            0 -> false
            3 -> true
            else -> error("Graphic $id has $padding bytes between its pixels and its palette.")
        }
    }
}
