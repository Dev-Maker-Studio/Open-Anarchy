package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SpriteDefinition
import world.gregs.voidps.cache.definition.decoder.SpriteDecoder
import world.gregs.voidps.cache.source.codec.BaselineJpeg
import world.gregs.voidps.cache.source.codec.JagexJpeg
import world.gregs.voidps.cache.source.codec.SpriteCodec
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Index 32's loading screens: the four component JPEGs, their split into two ordinary ones, and the
 * client's rules for reading them.
 *
 * Four things are proved here, in the order they matter.
 *
 * 1. **Parity.** Every JPEG archive splits into `<id>.jpg` and `<id>.alpha.jpg` and joins back to
 *    the archive's bytes *byte for byte* - which is what lets the tree store them as editable images
 *    with no `.pristine` copy beside either half.
 * 2. **Both halves are ordinary JPEGs.** `javax.imageio` reads the colour half as a three band sRGB
 *    image and the alpha half as one band of grey, and neither carries an application segment, so
 *    any viewer or editor opens them.
 * 3. **The decode rules are the client's.** Index 34 holds the same 34 pictures as palette sprites -
 *    it is the branch the client takes when AWT will not decode these files - so it is an
 *    independent copy of the same art to measure against, and [JagexJpeg.decode] lands within a few
 *    levels of it. Nothing in this test asserts "the colours look plausible": it asserts they are
 *    the colours Jagex shipped in the other format.
 * 4. **An edit survives.** Re-saving either half through `ImageIO` - new quantisation tables, a JFIF
 *    segment, its own Huffman tables - still joins into a four component file whose pixels and alpha
 *    come back where they were put.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class LoadingScreenCodecTest {

    @Test
    fun `every four component loading screen splits into two ordinary JPEGs and joins back to the cache`() {
        val cache = cache() ?: return
        var jpegs = 0
        var sprites = 0
        var colourBytes = 0L
        var alphaBytes = 0L
        var originalBytes = 0L
        println("%-8s %-10s %8s %8s %8s %9s".format("archive", "size", "colour", "alpha", "cache", "overhead"))
        for (archive in cache.archiveIds(Index.LOADING_SPRITES)) {
            val group = cache.data(Index.LOADING_SPRITES, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                sprites++
                continue
            }
            jpegs++
            val parts = JagexJpeg.split(group)

            val colour = BaselineJpeg.read(parts.colour)
            val alpha = BaselineJpeg.read(parts.alpha)
            val original = BaselineJpeg.read(group)
            assertEquals(4, original.components.size, "archive $archive is not four components")
            assertEquals(3, colour.components.size, "archive $archive's colour half is not three components")
            assertEquals(1, alpha.components.size, "archive $archive's alpha half is not one component")
            assertEquals(original.width, colour.width, "archive $archive's colour half is a different width")
            assertEquals(original.height, colour.height, "archive $archive's colour half is a different height")
            assertEquals(original.width, alpha.width, "archive $archive's alpha half is a different width")
            assertEquals(original.height, alpha.height, "archive $archive's alpha half is a different height")
            for (index in 0 until 3) {
                assertEquals(
                    original.components[index].horizontal, colour.components[index].horizontal,
                    "archive $archive's component $index changed its horizontal sampling"
                )
                assertEquals(
                    original.components[index].vertical, colour.components[index].vertical,
                    "archive $archive's component $index changed its vertical sampling"
                )
            }
            // Jagex's layout: two quantisation tables, four Huffman tables, no APP or COM segment,
            // and 2x2, 1x1, 1x1, 2x2 over table selectors 0, 1, 1, 0.
            assertEquals(
                listOf(DQT, DQT, SOF0, DHT, DHT, DHT, DHT, SOS), markers(group),
                "archive $archive is not Jagex's marker layout"
            )
            assertEquals(
                listOf(2 to 2, 1 to 1, 1 to 1, 2 to 2),
                original.components.map { it.horizontal to it.vertical },
                "archive $archive is not 4:2:0 luminance plus a full resolution fourth component"
            )
            assertEquals(
                listOf(0, 1, 1, 0), original.components.map { it.quantisation },
                "archive $archive's fourth component is not quantised like its luminance"
            )
            for (half in listOf(parts.colour, parts.alpha)) {
                assertTrue(
                    markers(half).none { it in APPLICATION || it == COM },
                    "archive $archive's halves must carry no application segment"
                )
            }

            // Both halves through a standard reader, which is the whole point of the split: the
            // four component original makes ImageIO.read throw and these do not.
            val readable = ImageIO.read(ByteArrayInputStream(parts.colour))
            assertNotNull(readable, "archive $archive's colour half is not readable by ImageIO")
            assertEquals(3, readable.raster.numBands, "archive $archive's colour half is not three bands")
            assertEquals(original.width, readable.width, "archive $archive's colour half read a different width")
            val grey = ImageIO.read(ByteArrayInputStream(parts.alpha))
            assertNotNull(grey, "archive $archive's alpha half is not readable by ImageIO")
            assertEquals(1, grey.raster.numBands, "archive $archive's alpha half is not one band")

            val joined = JagexJpeg.join(parts.colour, parts.alpha)
            assertTrue(
                joined.contentEquals(group),
                "archive $archive joined to ${joined.size} bytes, not the cache's ${group.size}"
            )
            colourBytes += parts.colour.size
            alphaBytes += parts.alpha.size
            originalBytes += group.size
            println(
                "%-8d %-10s %8d %8d %8d %8.1f%%".format(
                    archive, "${original.width}x${original.height}", parts.colour.size, parts.alpha.size,
                    group.size, 100.0 * (parts.colour.size + parts.alpha.size - group.size) / group.size
                )
            )
        }
        println(
            "$jpegs JPEG archives, $sprites palette sprite groups; $originalBytes cache bytes became " +
                "${colourBytes + alphaBytes} on disk (${"%.1f".format(100.0 * (colourBytes + alphaBytes - originalBytes) / originalBytes)}%)"
        )
        assertTrue(jpegs >= 21, "only $jpegs JPEG archives were found in index 32")
        // The rest of the index is the pre-cache fonts, which are palette sprite groups.
        assertTrue(sprites > 0, "index 32 has no palette sprite groups")
    }

    @Test
    fun `the decoded pixels are the ones Jagex shipped in index 34`() {
        val cache = cache() ?: return
        val decoder = SpriteDecoder()
        var compared = 0
        var withAlpha = 0
        println("%-8s %-10s %8s %8s %8s %9s".format("archive", "size", "colour", "solid", "clear", "pixels"))
        for (archive in cache.archiveIds(Index.LOADING_SPRITES)) {
            val group = cache.data(Index.LOADING_SPRITES, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                continue
            }
            val raw = cache.data(Index.LOADING_SPRITES_RAW, archive) ?: continue
            val definition = SpriteDefinition(archive)
            decoder.readLoop(definition, BufferReader(raw))
            val frames = definition.sprites ?: continue
            if (frames.size != 1) {
                continue
            }
            val frame = frames[0]
            val image = JagexJpeg.decode(group)
            if (frame.width != image.width || frame.height != image.height) {
                continue
            }
            var colourError = 0L
            var opaque = 0L
            var solid = 0L
            var solidAlpha = 0L
            var clear = 0L
            var clearAlpha = 0L
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val offset = x + y * frame.width
                    val index = frame.raster[offset].toInt() and 0xff
                    // Index 34 carries transparency two ways: an 8 bit plane where the frame has
                    // one, and palette index 0 - the client's transparent colour - where it does
                    // not. 3764 and 3765 use the second, so both have to be read here.
                    val plane = frame.alpha
                    val opacity = when {
                        plane != null -> plane[offset].toInt() and 0xff
                        index == 0 -> 0
                        else -> 0xff
                    }
                    val pixel = image.getRGB(x, y)
                    val alpha = pixel ushr 24
                    if (opacity == 0xff) {
                        solid++
                        solidAlpha += alpha.toLong()
                    } else if (opacity == 0) {
                        clear++
                        clearAlpha += alpha.toLong()
                    }
                    if (opacity != 0xff || alpha != 0xff) {
                        continue
                    }
                    val expected = frame.palette[index]
                    colourError += abs(((pixel shr 16) and 0xff) - ((expected shr 16) and 0xff)).toLong()
                    colourError += abs(((pixel shr 8) and 0xff) - ((expected shr 8) and 0xff)).toLong()
                    colourError += abs((pixel and 0xff) - (expected and 0xff)).toLong()
                    opaque++
                }
            }
            val colour = colourError / (opaque * 3.0)
            val inside = if (solid == 0L) Double.NaN else solidAlpha / solid.toDouble()
            val outside = if (clear == 0L) Double.NaN else clearAlpha / clear.toDouble()
            println(
                "%-8d %-10s %8.2f %8.1f %8.1f %9d".format(
                    archive, "${frame.width}x${frame.height}", colour, inside, outside, opaque
                )
            )
            assertTrue(opaque > 0, "archive $archive has no pixel opaque in both formats to compare")
            // Two independent encodings of one picture: a q75 JPEG and a 256 colour palette, so a
            // few levels apart is the whole disagreement. A CMYK or YCCK reading of the same file
            // is off by ninety and more, which is the failure this number is here to catch.
            assertTrue(
                colour < COLOUR_TOLERANCE,
                "archive $archive decodes $colour levels away from index 34's copy of the same picture"
            )
            // Straight alpha, not inverted. The two formats draw an *edge* differently - the JPEG
            // has eight bits of it and index 34 a hard palette index - so comparing them pixel for
            // pixel would only measure the edge's width. What is compared instead is the direction:
            // where index 34 says a pixel is solid the fourth component is near 255, and where it
            // says the pixel is not there at all it is near 0. Under `255 - v` the two swap.
            assertTrue(
                inside > SOLID,
                "archive $archive's alpha averages $inside where index 34 says the picture is solid"
            )
            if (clear > 0) {
                assertTrue(
                    outside < CLEAR,
                    "archive $archive's alpha averages $outside where index 34 says there is nothing"
                )
            }
            compared++
            if (frame.alpha != null || frame.raster.any { it.toInt() == 0 }) {
                withAlpha++
            }
        }
        assertTrue(compared >= 21, "only $compared loading screens could be compared with index 34")
        assertTrue(withAlpha > 0, "no compared loading screen had any transparency, so nothing proved the alpha straight")
    }

    @Test
    fun `a re-saved colour half still joins into a four component file the client decodes`() {
        val cache = cache() ?: return
        val (archive, group) = largest(cache) ?: return
        val parts = JagexJpeg.split(group)
        val original = JagexJpeg.decode(group)

        // What an image editor does to the file: its own quantisation and Huffman tables, a JFIF
        // segment, and no idea any of this was ever one image with a fourth component.
        val edited = ImageIO.read(ByteArrayInputStream(parts.colour))
        for (y in 0 until HOLE) {
            for (x in 0 until HOLE) {
                edited.setRGB(x, y, RED)
            }
        }
        val resaved = resave(edited, "JPEG", 0.95f)
        assertTrue(markers(resaved).contains(APP0), "the re-saved half should carry the JFIF segment ImageIO writes")

        val joined = JagexJpeg.join(resaved, parts.alpha)
        val rebuilt = BaselineJpeg.read(joined)
        assertEquals(4, rebuilt.components.size, "archive $archive did not rejoin into four components")
        assertEquals(listOf(1, 2, 3, 4), rebuilt.components.map { it.id }, "the joined component ids changed")
        assertTrue(markers(joined).none { it in APPLICATION }, "a joined file must carry no application segment")

        val decoded = JagexJpeg.decode(joined)
        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
        val painted = decoded.getRGB(HOLE / 2, HOLE / 2)
        assertEquals(0xff, painted ushr 24, "the joined file lost its alpha")
        assertTrue(
            abs(((painted shr 16) and 0xff) - 0xff) < EDIT_TOLERANCE &&
                ((painted shr 8) and 0xff) < EDIT_TOLERANCE && (painted and 0xff) < EDIT_TOLERANCE,
            "the painted block came back as ${Integer.toHexString(painted)}, not red"
        )
        var error = 0L
        var pixels = 0L
        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                if (x < HOLE * 2 && y < HOLE * 2) {
                    continue
                }
                val was = original.getRGB(x, y)
                val now = decoded.getRGB(x, y)
                error += abs(((was shr 16) and 0xff) - ((now shr 16) and 0xff)).toLong()
                error += abs(((was shr 8) and 0xff) - ((now shr 8) and 0xff)).toLong()
                error += abs((was and 0xff) - (now and 0xff)).toLong()
                pixels++
            }
        }
        val mean = error / (pixels * 3.0)
        println("archive $archive re-saved and rejoined: ${joined.size} bytes, mean channel drift $mean")
        assertTrue(mean < COLOUR_TOLERANCE, "a re-saved half drifted $mean levels, which is more than a re-encode costs")
    }

    @Test
    fun `a re-saved alpha half changes the alpha the client reads`() {
        val cache = cache() ?: return
        val (archive, group) = largest(cache) ?: return
        val parts = JagexJpeg.split(group)
        val plane = ImageIO.read(ByteArrayInputStream(parts.alpha))
        assertEquals(0xff, plane.raster.getSample(0, 0, 0), "this archive's alpha was expected to start opaque")
        for (y in 0 until HOLE) {
            for (x in 0 until HOLE) {
                plane.raster.setSample(x, y, 0, 0)
            }
        }
        val joined = JagexJpeg.join(parts.colour, resave(plane, "JPEG", 0.95f))
        val decoded = JagexJpeg.decode(joined)
        assertEquals(4, BaselineJpeg.read(joined).components.size)
        val hole = decoded.getRGB(HOLE / 2, HOLE / 2) ushr 24
        val outside = decoded.getRGB(decoded.width - 1, decoded.height - 1) ushr 24
        println("archive $archive with a ${HOLE}x$HOLE hole punched in its alpha: inside $hole, outside $outside")
        assertTrue(hole < EDIT_TOLERANCE, "the punched hole is $hole, not transparent")
        assertTrue(outside > 0xff - EDIT_TOLERANCE, "the rest of the alpha stopped being opaque")
    }

    /**
     * Every loading screen written out where somebody can look at it: the two halves exactly as
     * `SpriteCodec` puts them in the source tree, and two PNGs of each - the colour half as any
     * viewer shows it, and the whole four component archive as the client's rules read it, alpha
     * included, which is the only difference between the two pictures.
     *
     * Not a parity check - that is the first test - but it is how the tree's own files are
     * regenerated after a change here, and how anybody sees whether the colours came out right.
     */
    @Test
    fun `every loading screen is written out where somebody can look at it`() {
        val cache = cache() ?: return
        val directory = File(OUTPUT)
        directory.mkdirs()
        var written = 0
        for (archive in cache.archiveIds(Index.LOADING_SPRITES)) {
            val group = cache.data(Index.LOADING_SPRITES, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                continue
            }
            val parts = JagexJpeg.split(group)
            val colour = File(directory, "$archive${SpriteCodec.JPEG_EXTENSION}")
            colour.writeBytes(parts.colour)
            File(directory, "$archive${SpriteCodec.JPEG_ALPHA_EXTENSION}").writeBytes(parts.alpha)
            val preview = File(directory, "loading-$archive.png")
            ImageIO.write(ImageIO.read(ByteArrayInputStream(parts.colour)), "png", preview)
            ImageIO.write(JagexJpeg.decode(group), "png", File(directory, "loading-$archive-rgba.png"))
            assertTrue(preview.length() > 0, "nothing was written to ${preview.absolutePath}")
            written++
        }
        println("wrote $written loading screens to ${directory.absolutePath}")
    }

    @Test
    fun `a JPEG archive maps both of its halves back to its archive`() {
        assertEquals(3770, SpriteCodec.archiveOf("3770${SpriteCodec.JPEG_EXTENSION}"))
        assertEquals(3770, SpriteCodec.archiveOf("3770${SpriteCodec.JPEG_ALPHA_EXTENSION}"))
        assertEquals(null, SpriteCodec.archiveOf("3770.alpha.dat"))
    }

    /** The biggest JPEG archive of index 32, which is the one worth looking at. */
    private fun largest(cache: Cache): Pair<Int, ByteArray>? {
        var best: Pair<Int, ByteArray>? = null
        var area = 0
        for (archive in cache.archiveIds(Index.LOADING_SPRITES)) {
            val group = cache.data(Index.LOADING_SPRITES, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                continue
            }
            val image = BaselineJpeg.read(group)
            if (image.width * image.height > area) {
                area = image.width * image.height
                best = archive to group
            }
        }
        return best
    }

    /** [image] through the standard writer at [quality], which is what "the user re-saved it" means. */
    private fun resave(image: BufferedImage, format: String, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName(format).next()
        try {
            val out = ByteArrayOutputStream(image.width * image.height)
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    /** Every marker of [data] up to and including its scan header, in order. */
    private fun markers(data: ByteArray): List<Int> {
        val out = ArrayList<Int>(8)
        var position = 2
        while (position + 3 < data.size) {
            if ((data[position].toInt() and 0xff) != 0xff) {
                break
            }
            val marker = data[position + 1].toInt() and 0xff
            out.add(marker)
            if (marker == SOS) {
                break
            }
            position += 2 + (((data[position + 2].toInt() and 0xff) shl 8) or (data[position + 3].toInt() and 0xff))
        }
        return out
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping loading screen codec test; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }

    private companion object {
        private const val SOF0 = 0xc0
        private const val DHT = 0xc4
        private const val SOS = 0xda
        private const val DQT = 0xdb
        private const val APP0 = 0xe0
        private const val COM = 0xfe
        private val APPLICATION = 0xe0..0xef

        /**
         * Mean absolute channel difference allowed between a q75 JPEG and index 34's 256 colour
         * palette of the same art. The worst of the 29 measures about ten; a CMYK reading measures
         * ninety and more.
         */
        private const val COLOUR_TOLERANCE = 16.0

        /** What the fourth component must average where index 34 says the picture is solid, and where it is not. */
        private const val SOLID = 200.0

        private const val CLEAR = 64.0

        /** How far one pixel may move through a JPEG re-encode before it is a different pixel. */
        private const val EDIT_TOLERANCE = 24

        private const val HOLE = 32

        private const val RED = 0xffff0000.toInt()

        private const val OUTPUT = "build/loading-screens"
    }
}
