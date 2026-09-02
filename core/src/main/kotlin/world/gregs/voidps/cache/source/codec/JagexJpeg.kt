package world.gregs.voidps.cache.source.codec

import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.roundToInt

/**
 * The four component JPEGs index 32 holds its loading screens in, and how they become two files
 * anything can open.
 *
 * Jagex's loading screen art is a **baseline JPEG with four components and no Adobe APP14 marker**:
 * `Y`, `Cb`, `Cr` and an alpha plane. The stream opens straight on `ff d8 ff db` - there is no JFIF
 * APP0 either - and all 29 of them have the same frame:
 *
 * ```
 * ffdb(67) ffdb(67)     the standard IJG luminance and chrominance tables, scaled 0.5, i.e. q75
 * ffc0(20) id1 2x2 q0   Y,     full resolution
 *          id2 1x1 q1   Cb,    subsampled 2:1 both ways
 *          id3 1x1 q1   Cr,    subsampled 2:1 both ways
 *          id4 2x2 q0   alpha, full resolution, quantised and coded like luminance
 * ffc4(31) ffc4(181)    the standard DC and AC luminance tables, ids 0
 * ffc4(31) ffc4(181)    the standard DC and AC chrominance tables, ids 1
 * ffda(14)              one baseline scan interleaving all four, then EOI
 * ```
 *
 * Four components with no Adobe marker is what every standard viewer reads as CMYK or YCCK, which is
 * why these files show up cyan and magenta everywhere: the fourth component is *alpha*, and nothing
 * in the file says so. `ImageIO.read` and `Toolkit.createImage` do not merely guess wrong, they
 * refuse - `libjpeg` types the stream `JCS_CMYK`, AWT asks it for RGB and it throws
 * `Unsupported color conversion request`.
 *
 * **What the client does.** Nothing, is the honest answer.
 * `NativeSprite.fromPixelgrabber` hands the container bytes to `Toolkit.getDefaultToolkit().createImage`
 * and grabs `TYPE_INT_ARGB` ints back out with a `PixelGrabber`; there is no YCbCr matrix, no APP14
 * handling and no alpha branch anywhere in it, so every rule below belongs to the platform decoder.
 * The alpha reaches the GPU as `GL_BGRA` straight from those ints, un-premultiplied. The client even
 * knows the path is unreliable: `NativeSprite.supportsGzippedSprites` decodes one embedded 1x1 file
 * of exactly this shape at startup and, when AWT throws, sets `Resource.USING_JAGEX_IMAGE_FORMAT` and
 * reads index 34 - the same 34 pictures as palette sprites - instead. On any modern JVM that probe
 * fails, which makes index 34 a ready made cross check for [decode] and is what
 * `LoadingScreenCodecTest` measures against.
 *
 * So the rules, taken from the files themselves and confirmed against index 34:
 *
 * - components 1..3 are `Y`, `Cb`, `Cr` with the usual 128 offset on the two chroma planes, and the
 *   standard JFIF (ITU-R BT.601) matrix converts them;
 * - component 4 is **straight alpha**, 255 opaque, no inversion and no offset - 27 of the 29 files
 *   are a constant 255 and the two that are not (3764, 3765) hold the sprite's silhouette at 255 on
 *   a 0 ground, which would be inside out were it `255 - v`;
 * - no colour transform of any kind applies, because with no Adobe marker there is no transform flag
 *   to apply one.
 *
 * ## How the source tree stores them
 *
 * [split] takes the file apart at the DCT coefficient level - see [BaselineJpeg] - into
 *
 * - `<id>.jpg`, an ordinary three component baseline JPEG of `Y`, `Cb` and `Cr`, and
 * - `<id>.alpha.jpg`, an ordinary one component greyscale JPEG of the fourth,
 *
 * each keeping the original's quantisation tables, Huffman tables, sampling factors and *the very
 * coefficients that were in the cache*, merely re-entropy-coded. Nothing is dequantised, inverse
 * transformed or re-compressed, so [join] puts the two back together into the cache's own bytes -
 * byte for byte, all 29 - and the tree needs no `.pristine` copy of anything. Both halves open in
 * any viewer or editor, which was the whole point.
 *
 * The one thing a standalone one component JPEG cannot hold is the block grid's **MCU padding**. In
 * the interleaved four component scan a 2x2 component is stored on the MCU grid, which for six of
 * the 29 files (3764, 3765, 3773, 3774, 3775, 3776) runs a block past the image; alone in its own
 * file the alpha is coded on its own smaller grid and those blocks have nowhere to go. They are not
 * information: they are what `libjpeg`'s `compress_data` fabricates at an edge - all AC zero, and the
 * DC copied from the block before - so [join] rebuilds them, which is [padding], and byte for byte
 * parity on those six files is the proof the rule is the right one.
 */
object JagexJpeg {

    /** A four component Jagex JPEG as the two files a source tree keeps it in. */
    class Parts(
        /** The `Y`, `Cb` and `Cr` components as a three component baseline JPEG. */
        val colour: ByteArray,
        /** The fourth component as a one component greyscale baseline JPEG. */
        val alpha: ByteArray
    )

    /** Whether [data] is a JPEG container rather than something else - the `ff d8` start of image. */
    fun isJpeg(data: ByteArray): Boolean =
        data.size > 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1

    /**
     * [data] as the colour and alpha halves the source tree stores, losing nothing.
     *
     * @throws IOException when [data] is not a four component baseline JPEG, which is every JPEG in
     *   index 32 and the only shape [join] can put back together.
     */
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
        // Alone in its own frame the plane is a full resolution single component, so 1x1, and its
        // grid shrinks to the image's own blocks; the MCU padding it drops is what padding() rebuilds.
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

    /**
     * The two halves back into the one four component file the client reads.
     *
     * Byte for byte the archive [split] came from while neither half has been edited. Once one has,
     * whatever tables the editor wrote are what comes out: the alpha's quantisation and Huffman
     * tables are matched against the colour file's by content and share an id where they are the
     * same table - which is what reproduces Jagex's own two `ffdb` and four `ffc4` segments - and get
     * an id of their own where they are not.
     */
    fun join(colour: ByteArray, alpha: ByteArray): ByteArray {
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
        // Alpha is never subsampled, so it takes the frame's largest factors - 2x2 in the cache's
        // own files, and whatever an edited colour file uses otherwise.
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

    /**
     * [data] as a straight (un-premultiplied) `TYPE_INT_ARGB` image, by the client's rules.
     *
     * The only way to read one of these: [javax.imageio.ImageReader.readRaster] hands back the
     * components exactly as they were coded, with no colour conversion attempted and so nothing to
     * refuse. Three component files - none are in 727, but the sniff is on the magic and not on a
     * list of archives - decode the same way with every pixel opaque.
     */
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

    /**
     * Fill every block of [component] outside [across] by [down] the way `libjpeg` does at an edge:
     * all AC zero, and the DC copied from the block before it.
     *
     * "Before it" is along the row for a block past the right edge, and for a dummy row it is the
     * last block of the row above *within the same MCU* - which is why every block of such a row
     * ends up with the one value. That is `jccoefct.c`'s `compress_data` exactly, and it is what
     * makes the six files with an odd block count rebuild byte for byte.
     */
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

    /** [want]'s id in [tables]: the id that already holds it, else the lowest free one. */
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

    /** The components exactly as they were coded, which is the only read that does not colour convert. */
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

    /** Y, Cb, Cr and alpha, and so also the fourth component's id. */
    private const val COMPONENTS = 4

    private const val OPAQUE = 255

    private const val HALF = 128

    /** ITU-R BT.601, the matrix JFIF specifies and every JPEG in this cache was written with. */
    private const val R_CR = 1.402
    private const val G_CB = 0.344136
    private const val G_CR = 0.714136
    private const val B_CB = 1.772

    private const val MAGIC_0 = 0xff.toByte()

    private const val MAGIC_1 = 0xd8.toByte()
}
