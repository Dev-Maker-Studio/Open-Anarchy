package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.SpriteDefinition

/**
 * The exact inverse of [world.gregs.voidps.cache.definition.decoder.SpriteDecoder].
 *
 * The file is three blocks: the frames' pixels from offset 0, then the palette, then a fixed
 * `7 + 8 * frames` byte trailer. Nothing here decides anything - every frame writes the storage
 * order and the alpha plane its own [world.gregs.voidps.cache.definition.data.IndexedSprite.flags]
 * calls for, and the palette is written from
 * [SpriteDefinition.rawPalette] rather than from the normalised copy the frames share.
 *
 * An alpha plane the decoder dropped because every byte of it was 0xff is written back as 0xff:
 * that is what "no transparency" means in the file, and the flag is what says the plane is there.
 */
class SpriteEncoder {

    fun Writer.encode(definition: SpriteDefinition) {
        val sprites = definition.sprites ?: error("Sprite ${definition.id} has no frames to encode.")
        for (sprite in sprites) {
            writeByte(sprite.flags)
            val area = sprite.width * sprite.height
            require(sprite.raster.size == area) {
                "Sprite ${definition.id} frame is ${sprite.width}x${sprite.height} but holds ${sprite.raster.size} pixels."
            }
            if (sprite.flags and 0x1 == 0) {
                writeBytes(sprite.raster)
            } else {
                for (x in 0 until sprite.width) {
                    for (y in 0 until sprite.height) {
                        writeByte(sprite.raster[x + y * sprite.width].toInt())
                    }
                }
            }
            if (sprite.flags and 0x2 == 0) {
                continue
            }
            val alpha = sprite.alpha
            if (alpha == null) {
                for (pixel in 0 until area) {
                    writeByte(OPAQUE)
                }
            } else {
                require(alpha.size == area) {
                    "Sprite ${definition.id} frame has ${alpha.size} alpha bytes for $area pixels."
                }
                if (sprite.flags and 0x1 == 0) {
                    writeBytes(alpha)
                } else {
                    for (x in 0 until sprite.width) {
                        for (y in 0 until sprite.height) {
                            writeByte(alpha[x + y * sprite.width].toInt())
                        }
                    }
                }
            }
        }

        val palette = definition.rawPalette
        require(palette.isNotEmpty()) { "Sprite ${definition.id} has no palette; entry 0 is always present." }
        require(palette.size <= PALETTE_LIMIT) {
            "Sprite ${definition.id} has ${palette.size} palette entries; the size is a byte plus one."
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
        for (sprite in sprites) {
            writeShort(sprite.offsetX)
        }
        for (sprite in sprites) {
            writeShort(sprite.offsetY)
        }
        for (sprite in sprites) {
            writeShort(sprite.width)
        }
        for (sprite in sprites) {
            writeShort(sprite.height)
        }
        writeShort(sprites.size)
    }

    private companion object {
        /** The alpha byte of a fully opaque pixel, which is what a dropped alpha plane was made of. */
        private const val OPAQUE = 0xff

        /** The palette size is stored as `size - 1` in one byte. */
        private const val PALETTE_LIMIT = 256
    }
}
