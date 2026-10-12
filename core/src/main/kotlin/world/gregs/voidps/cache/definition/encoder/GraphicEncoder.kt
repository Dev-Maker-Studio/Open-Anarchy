package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.GraphicDefinition

class GraphicEncoder {

    fun Writer.encode(definition: GraphicDefinition) {
        val graphics = definition.graphics ?: error("Graphic ${definition.id} has no frames to encode.")
        for (graphic in graphics) {
            writeByte(graphic.flags)
            val area = graphic.width * graphic.height
            require(graphic.raster.size == area) {
                "Graphic ${definition.id} frame is ${graphic.width}x${graphic.height} but holds ${graphic.raster.size} pixels."
            }
            if (graphic.flags and 0x1 == 0) {
                writeBytes(graphic.raster)
            } else {
                for (x in 0 until graphic.width) {
                    for (y in 0 until graphic.height) {
                        writeByte(graphic.raster[x + y * graphic.width].toInt())
                    }
                }
            }
            if (graphic.flags and 0x2 == 0) {
                continue
            }
            val alpha = graphic.alpha
            if (alpha == null) {
                for (pixel in 0 until area) {
                    writeByte(OPAQUE)
                }
            } else {
                require(alpha.size == area) {
                    "Graphic ${definition.id} frame has ${alpha.size} alpha bytes for $area pixels."
                }
                if (graphic.flags and 0x1 == 0) {
                    writeBytes(alpha)
                } else {
                    for (x in 0 until graphic.width) {
                        for (y in 0 until graphic.height) {
                            writeByte(alpha[x + y * graphic.width].toInt())
                        }
                    }
                }
            }
        }

        val palette = definition.rawPalette
        require(palette.isNotEmpty()) { "Graphic ${definition.id} has no palette; entry 0 is always present." }
        require(palette.size <= PALETTE_LIMIT) {
            "Graphic ${definition.id} has ${palette.size} palette entries; the size is a byte plus one."
        }
        if (definition.paletteZeroEntry) {
            writeMedium(palette[0])
        }
        for (index in 1 until palette.size) {
            writeMedium(palette[index])
        }

        writeShort(definition.width)
        writeShort(definition.height)
        writeByte(palette.size - 1)
        for (graphic in graphics) {
            writeShort(graphic.offsetX)
        }
        for (graphic in graphics) {
            writeShort(graphic.offsetY)
        }
        for (graphic in graphics) {
            writeShort(graphic.width)
        }
        for (graphic in graphics) {
            writeShort(graphic.height)
        }
        writeShort(graphics.size)
    }

    private companion object {
        private const val OPAQUE = 0xff

        private const val PALETTE_LIMIT = 256
    }
}
