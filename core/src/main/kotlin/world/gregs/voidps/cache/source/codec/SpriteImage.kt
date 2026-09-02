package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.definition.data.IndexedSprite
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * One sprite frame as a pair of standard PNGs.
 *
 * A frame is palette indices, so it is written as an **indexed colour** PNG: the group's palette
 * becomes the `PLTE` chunk in its original order and the pixels stay the indices they were, which
 * is what makes the round trip exact rather than approximate. Nothing about the image is
 * interpreted on the way back - only `raster.getSample` - so a frame survives being opened, edited
 * and saved by anything that keeps the palette.
 *
 * Per pixel alpha does not fit a palette, so the format byte decides which of two shapes is used:
 *
 * - no alpha plane: the PNG carries a `tRNS` chunk making index 0 transparent, because index 0 is
 *   the client's transparent colour and a viewer that shows it as black shows the wrong picture;
 * - an alpha plane: the indexed PNG carries no `tRNS` - every index is opaque in it - and the
 *   plane rides beside it as an 8 bit greyscale PNG.
 *
 * The images are written through in-memory streams rather than `ImageIO.write`, which otherwise
 * spills to a temporary file per image and is process-wide state to turn off.
 */
internal object SpriteImage {

    /** The frame's palette indices as an indexed colour PNG over [palette]. */
    fun write(sprite: IndexedSprite, palette: IntArray, transparent: Boolean): ByteArray {
        val model = model(palette, transparent)
        val image = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_BYTE_INDEXED, model)
        val raster = image.raster
        for (y in 0 until sprite.height) {
            for (x in 0 until sprite.width) {
                raster.setSample(x, y, 0, sprite.raster[x + y * sprite.width].toInt() and 0xff)
            }
        }
        return png(image)
    }

    /** An alpha plane as an 8 bit greyscale PNG. */
    fun writeAlpha(sprite: IndexedSprite, alpha: ByteArray): ByteArray {
        val image = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_BYTE_GRAY)
        val raster = image.raster
        for (y in 0 until sprite.height) {
            for (x in 0 until sprite.width) {
                raster.setSample(x, y, 0, alpha[x + y * sprite.width].toInt() and 0xff)
            }
        }
        return png(image)
    }

    /** A PNG's single band back as one byte per pixel, row major, with its dimensions checked. */
    fun read(bytes: ByteArray, width: Int, height: Int, name: String): ByteArray {
        val image = decode(bytes, name)
        require(image.width == width && image.height == height) {
            "$name is ${image.width}x${image.height} but its sprite says ${width}x$height."
        }
        val raster = image.raster
        require(raster.numBands == 1) {
            "$name has ${raster.numBands} bands; a sprite frame is one band of palette indices."
        }
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x + y * width] = raster.getSample(x, y, 0).toByte()
            }
        }
        return out
    }

    /**
     * The image, through a reader this owns.
     *
     * `ImageIO.read` disposes the stream it is handed, so the stream cannot also be closed by the
     * caller; driving the reader directly keeps both halves of this file symmetrical.
     */
    private fun decode(bytes: ByteArray, name: String): BufferedImage {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val readers = ImageIO.getImageReaders(stream)
        require(readers.hasNext()) { "$name is not an image this build can read." }
        val reader = readers.next()
        try {
            reader.input = stream
            return reader.read(0)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun model(palette: IntArray, transparent: Boolean): IndexColorModel {
        val size = palette.size
        val red = ByteArray(size)
        val green = ByteArray(size)
        val blue = ByteArray(size)
        for (index in 0 until size) {
            red[index] = (palette[index] shr 16).toByte()
            green[index] = (palette[index] shr 8).toByte()
            blue[index] = palette[index].toByte()
        }
        return if (transparent) {
            IndexColorModel(BITS, size, red, green, blue, 0)
        } else {
            IndexColorModel(BITS, size, red, green, blue)
        }
    }

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height + HEADER)
        val writer = ImageIO.getImageWritersByFormatName(FORMAT).next()
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(image)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** One byte per pixel: the palette is written whole rather than packed into 1, 2 or 4 bits. */
    private const val BITS = 8

    private const val FORMAT = "png"

    /** Roughly what a PNG's chunks cost before any pixels. */
    private const val HEADER = 1024
}
