package world.gregs.voidps.cache.source.codec

import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

object JagexJpeg {

    class Parts(
        val colour: ByteArray,
        val alpha: ByteArray
    )

    fun isJpeg(data: ByteArray): Boolean =
        data.size > 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1

    fun editable(archive: ByteArray): ByteArray {
        val parts = split(archive)
        return embed(parts.colour, parts.alpha)
    }

    fun archive(file: ByteArray): ByteArray = join(file, alphaOf(file))

    fun archive(image: BufferedImage): ByteArray {
        val width = image.width
        val height = image.height
        val colour = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val plane = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val row = IntArray(width)
        for (y in 0 until height) {
            image.getRGB(0, y, width, 1, row, 0, width)
            for (x in 0 until width) {
                colour.setRGB(x, y, row[x] and 0xffffff)
                plane.raster.setSample(x, y, 0, row[x] ushr 24)
            }
        }
        return join(jpeg(colour), jpeg(plane))
    }

    fun embed(colour: ByteArray, alpha: ByteArray): ByteArray {
        if (!isJpeg(colour)) {
            throw IOException("An embedded alpha needs a JPEG to ride in.")
        }
        val segments = maxOf(BaselineJpeg.ceil(alpha.size, PAYLOAD), 1)
        if (segments > 0xffff) {
            throw IOException("An alpha codestream of ${alpha.size} bytes needs $segments APP11 segments.")
        }
        val out = ByteArrayOutputStream(colour.size + alpha.size + segments * OVERHEAD)
        out.write(colour, 0, 2)
        var offset = 0
        for (sequence in 1..segments) {
            val length = minOf(PAYLOAD, alpha.size - offset)
            val size = 2 + IDENTIFIER.size + 4 + length
            out.write(0xff)
            out.write(APP11)
            out.write(size shr 8)
            out.write(size and 0xff)
            out.write(IDENTIFIER)
            out.write(sequence shr 8)
            out.write(sequence and 0xff)
            out.write(segments shr 8)
            out.write(segments and 0xff)
            out.write(alpha, offset, length)
            offset += length
        }
        out.write(colour, 2, colour.size - 2)
        return out.toByteArray()
    }

    fun alphaOf(file: ByteArray): ByteArray? {
        if (!isJpeg(file)) {
            return null
        }
        val parts = HashMap<Int, ByteArray>()
        var segments = 0
        var position = 2
        while (position + 3 < file.size) {
            if ((file[position].toInt() and 0xff) != 0xff) {
                break
            }
            val marker = file[position + 1].toInt() and 0xff
            if (marker == SOS || marker == EOI) {
                break
            }
            val length = ((file[position + 2].toInt() and 0xff) shl 8) or (file[position + 3].toInt() and 0xff)
            val end = position + 2 + length
            if (end > file.size) {
                break
            }
            if (marker == APP11 && length >= 2 + IDENTIFIER.size + 4 && identifier(file, position + 4)) {
                val start = position + 4 + IDENTIFIER.size
                val sequence = ((file[start].toInt() and 0xff) shl 8) or (file[start + 1].toInt() and 0xff)
                segments = ((file[start + 2].toInt() and 0xff) shl 8) or (file[start + 3].toInt() and 0xff)
                parts[sequence] = file.copyOfRange(start + 4, end)
            }
            position = end
        }
        if (parts.isEmpty()) {
            return null
        }
        if (segments != parts.size) {
            throw IOException("An embedded alpha says it is $segments segments but ${parts.size} are in the file.")
        }
        val out = ByteArrayOutputStream(parts.values.sumOf { it.size })
        for (sequence in 1..segments) {
            out.write(parts[sequence] ?: throw IOException("An embedded alpha is missing segment $sequence of $segments."))
        }
        return out.toByteArray()
    }

    fun split(data: ByteArray): Parts {
        val image = BaselineJpeg.read(data)
        if (image.components.size != COMPONENTS) {
            throw IOException("A Jagex JPEG has four components; this one has ${image.components.size}.")
        }
        val colour = BaselineJpeg(image.width, image.height, restartInterval = image.restartInterval)
        for (index in 0 until COMPONENTS - 1) {
            val component = image.components[index]
            colour.quantisation[component.quantisation] = image.quantisation[component.quantisation]
            colour.dcTables[component.dc] = image.dcTables[component.dc]
            colour.acTables[component.ac] = image.acTables[component.ac]
            colour.components.add(component)
        }
        val fourth = image.components[COMPONENTS - 1]
        val alpha = BaselineJpeg(image.width, image.height, restartInterval = image.restartInterval)
        alpha.quantisation[0] = image.quantisation[fourth.quantisation]
        alpha.dcTables[0] = image.dcTables[fourth.dc]
        alpha.acTables[0] = image.acTables[fourth.ac]
        val across = BaselineJpeg.ceil(image.width, BaselineJpeg.BLOCK)
        val down = BaselineJpeg.ceil(image.height, BaselineJpeg.BLOCK)
        val plane = JpegComponent(1, 1, 1, 0)
        plane.across = across
        plane.down = down
        plane.coefficients = ShortArray(across * down * BaselineJpeg.BLOCK_SIZE)
        for (row in 0 until down) {
            fourth.coefficients.copyInto(
                plane.coefficients,
                row * across * BaselineJpeg.BLOCK_SIZE,
                row * fourth.across * BaselineJpeg.BLOCK_SIZE,
                (row * fourth.across + across) * BaselineJpeg.BLOCK_SIZE
            )
        }
        alpha.components.add(plane)
        return Parts(colour.write(), alpha.write())
    }

    fun join(colour: ByteArray, alpha: ByteArray?): ByteArray {
        if (alpha == null) {
            return opaque(BaselineJpeg.read(colour))
        }
        val image = BaselineJpeg.read(colour)
        val plane = BaselineJpeg.read(alpha)
        if (plane.components.size != 1) {
            throw IOException("An alpha JPEG is one component; this one has ${plane.components.size}.")
        }
        if (plane.width != image.width || plane.height != image.height) {
            throw IOException(
                "An alpha JPEG is ${plane.width}x${plane.height} but its colour JPEG is ${image.width}x${image.height}."
            )
        }
        val source = plane.components[0]
        val quantisation = assign(image.quantisation, plane.quantisation[source.quantisation], "quantisation")
        val dc = assign(image.dcTables, plane.dcTables[source.dc], "DC Huffman")
        val ac = assign(image.acTables, plane.acTables[source.ac], "AC Huffman")
        val horizontal = image.maxHorizontal
        val vertical = image.maxVertical
        val fourth = JpegComponent(COMPONENTS, horizontal, vertical, quantisation)
        fourth.dc = dc
        fourth.ac = ac
        fourth.across = image.mcusAcross * horizontal
        fourth.down = image.mcusDown * vertical
        fourth.coefficients = ShortArray(fourth.across * fourth.down * BaselineJpeg.BLOCK_SIZE)
        val across = BaselineJpeg.ceil(image.width, BaselineJpeg.BLOCK)
        val down = BaselineJpeg.ceil(image.height, BaselineJpeg.BLOCK)
        if (source.across < across || source.down < down) {
            throw IOException(
                "An alpha JPEG holds ${source.across}x${source.down} blocks where ${across}x$down are needed."
            )
        }
        for (row in 0 until down) {
            source.coefficients.copyInto(
                fourth.coefficients,
                row * fourth.across * BaselineJpeg.BLOCK_SIZE,
                row * source.across * BaselineJpeg.BLOCK_SIZE,
                (row * source.across + across) * BaselineJpeg.BLOCK_SIZE
            )
        }
        padding(fourth, across, down)
        image.components.add(fourth)
        return image.write()
    }

    fun decode(data: ByteArray): BufferedImage {
        val raster = raster(data)
        val width = raster.width
        val height = raster.height
        val bands = raster.numBands
        if (bands != COMPONENTS && bands != COMPONENTS - 1) {
            throw IOException("A Jagex JPEG is 3 or 4 components; this one is $bands.")
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val samples = IntArray(width * bands)
        val row = IntArray(width)
        for (y in 0 until height) {
            raster.getPixels(0, y, width, 1, samples)
            for (x in 0 until width) {
                val offset = x * bands
                val luma = samples[offset]
                val blue = samples[offset + 1] - HALF
                val red = samples[offset + 2] - HALF
                val alpha = if (bands == COMPONENTS) samples[offset + 3] else OPAQUE
                row[x] = (alpha shl 24) or
                    (clamp(luma + R_CR * red) shl 16) or
                    (clamp(luma - G_CB * blue - G_CR * red) shl 8) or
                    clamp(luma + B_CB * blue)
            }
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        return image
    }

    private fun opaque(image: BaselineJpeg): ByteArray {
        val luma = image.components[0]
        val table = image.quantisation[luma.quantisation]
            ?: throw IOException("A JPEG's luminance quantisation table is not defined.")
        val fourth = JpegComponent(COMPONENTS, image.maxHorizontal, image.maxVertical, luma.quantisation)
        fourth.dc = luma.dc
        fourth.ac = luma.ac
        fourth.across = image.mcusAcross * fourth.horizontal
        fourth.down = image.mcusDown * fourth.vertical
        fourth.coefficients = ShortArray(fourth.across * fourth.down * BaselineJpeg.BLOCK_SIZE)
        val level = BaselineJpeg.ceil(FLAT, table[0]).toShort()
        for (block in 0 until fourth.across * fourth.down) {
            fourth.coefficients[block * BaselineJpeg.BLOCK_SIZE] = level
        }
        image.components.add(fourth)
        return image.write()
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(FORMAT).next()
        try {
            val out = ByteArrayOutputStream(image.width * image.height)
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = QUALITY
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    private fun identifier(data: ByteArray, offset: Int): Boolean {
        for (index in IDENTIFIER.indices) {
            if (data[offset + index] != IDENTIFIER[index]) {
                return false
            }
        }
        return true
    }

    private fun padding(component: JpegComponent, across: Int, down: Int) {
        val mcusAcross = component.across / component.horizontal
        val mcusDown = component.down / component.vertical
        for (mcuY in 0 until mcusDown) {
            for (offsetY in 0 until component.vertical) {
                val blockY = mcuY * component.vertical + offsetY
                val dummy = blockY >= down
                if (dummy && offsetY == 0) {
                    throw IOException("An alpha JPEG's MCU row $mcuY is padding, which cannot happen.")
                }
                for (mcuX in 0 until mcusAcross) {
                    for (offsetX in 0 until component.horizontal) {
                        val blockX = mcuX * component.horizontal + offsetX
                        if (!dummy && blockX < across) {
                            continue
                        }
                        val offset = (blockY * component.across + blockX) * BaselineJpeg.BLOCK_SIZE
                        component.coefficients.fill(0, offset, offset + BaselineJpeg.BLOCK_SIZE)
                        val previous = if (dummy && offsetX == 0) {
                            (blockY - 1) * component.across + mcuX * component.horizontal + component.horizontal - 1
                        } else {
                            blockY * component.across + blockX - 1
                        }
                        component.coefficients[offset] =
                            component.coefficients[previous * BaselineJpeg.BLOCK_SIZE]
                    }
                }
            }
        }
    }

    private fun assign(tables: Array<IntArray?>, want: IntArray?, kind: String): Int {
        val values = want ?: throw IOException("An alpha JPEG's $kind table is not defined.")
        for (table in tables.indices) {
            if (values.contentEquals(tables[table])) {
                return table
            }
        }
        for (table in tables.indices) {
            if (tables[table] == null) {
                tables[table] = values
                return table
            }
        }
        throw IOException("A joined JPEG needs a fifth $kind table; baseline JPEG allows four.")
    }

    private fun assign(tables: Array<JpegHuffmanTable?>, want: JpegHuffmanTable?, kind: String): Int {
        val values = want ?: throw IOException("An alpha JPEG's $kind table is not defined.")
        for (table in tables.indices) {
            if (values.sameAs(tables[table])) {
                return table
            }
        }
        for (table in tables.indices) {
            if (tables[table] == null) {
                tables[table] = values
                return table
            }
        }
        throw IOException("A joined JPEG needs a fifth $kind table; baseline JPEG allows four.")
    }

    private fun raster(data: ByteArray): Raster {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(data))
        val readers = ImageIO.getImageReadersByFormatName(FORMAT)
        if (!readers.hasNext()) {
            throw IOException("This build has no JPEG reader.")
        }
        val reader = readers.next()
        try {
            reader.setInput(stream, true, true)
            return reader.readRaster(0, null)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun clamp(value: Double): Int {
        val rounded = value.roundToInt()
        return if (rounded < 0) 0 else if (rounded > OPAQUE) OPAQUE else rounded
    }

    private const val FORMAT = "JPEG"

    private const val COMPONENTS = 4

    private const val OPAQUE = 255

    private const val HALF = 128

    private const val R_CR = 1.402
    private const val G_CB = 0.344136
    private const val G_CR = 0.714136
    private const val B_CB = 1.772

    private const val MAGIC_0 = 0xff.toByte()

    private const val MAGIC_1 = 0xd8.toByte()

    private const val APP11 = 0xeb

    private const val SOS = 0xda

    private const val EOI = 0xd9

    private val IDENTIFIER = "JagexAlpha\u0000".toByteArray(Charsets.US_ASCII)

    private const val SEGMENT = 65533

    private const val PAYLOAD = SEGMENT - 11 - 4

    private const val OVERHEAD = 4 + 11 + 4

    private const val FLAT = 1016

    private const val QUALITY = 0.95f
}
