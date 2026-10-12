package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.source.codec.BaselineJpeg
import world.gregs.voidps.cache.source.codec.GraphicCodec
import world.gregs.voidps.cache.source.codec.JagexJpeg
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

class LoadingScreenCodecTest {

    @Test
    fun `every four component loading screen is one ordinary JPEG that joins back to the cache`() {
        val cache = cache() ?: return
        var jpegs = 0
        var graphics = 0
        var fileBytes = 0L
        var originalBytes = 0L
        println("%-8s %-10s %8s %8s %8s %9s".format("archive", "size", "file", "alpha", "cache", "overhead"))
        for (archive in cache.archiveIds(Index.LOADING_GRAPHICS)) {
            val group = cache.data(Index.LOADING_GRAPHICS, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                graphics++
                continue
            }
            jpegs++
            val file = JagexJpeg.editable(group)
            val original = BaselineJpeg.read(group)
            val colour = BaselineJpeg.read(file)
            assertEquals(4, original.components.size, "archive $archive is not four components")
            assertEquals(3, colour.components.size, "archive $archive's file is not three components")
            assertEquals(original.width, colour.width, "archive $archive's file is a different width")
            assertEquals(original.height, colour.height, "archive $archive's file is a different height")
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
            val stored = markers(file)
            assertEquals(APP11, stored[0], "archive $archive's file does not open on its alpha segment")
            assertTrue(
                stored.none { it in APPLICATION && it != APP11 } && stored.none { it == COM },
                "archive $archive's file carries an application segment that is not the alpha"
            )
            val alpha = JagexJpeg.alphaOf(file)
            assertNotNull(alpha, "archive $archive's file carries no alpha")
            assertEquals(1, BaselineJpeg.read(alpha).components.size, "archive $archive's alpha is not one component")

            val readable = ImageIO.read(ByteArrayInputStream(file))
            assertNotNull(readable, "archive $archive's file is not readable by ImageIO")
            assertEquals(3, readable.raster.numBands, "archive $archive's file is not three bands")
            assertEquals(original.width, readable.width, "archive $archive's file read a different width")
            assertEquals(original.height, readable.height, "archive $archive's file read a different height")

            val joined = JagexJpeg.archive(file)
            assertTrue(
                joined.contentEquals(group),
                "archive $archive joined to ${joined.size} bytes, not the cache's ${group.size}"
            )
            fileBytes += file.size
            originalBytes += group.size
            println(
                "%-8d %-10s %8d %8d %8d %8.1f%%".format(
                    archive, "${original.width}x${original.height}", file.size, alpha.size,
                    group.size, 100.0 * (file.size - group.size) / group.size
                )
            )
        }
        println(
            "$jpegs JPEG archives, $graphics palette graphic groups; $originalBytes cache bytes became " +
                "$fileBytes on disk (${"%.1f".format(100.0 * (fileBytes - originalBytes) / originalBytes)}%)"
        )
        assertTrue(jpegs >= 21, "only $jpegs JPEG archives were found in index 32")
        assertTrue(graphics > 0, "index 32 has no palette graphic groups")
    }

    @Test
    fun `the decoded pixels are the ones Jagex shipped in index 34`() {
        val cache = cache() ?: return
        val decoder = GraphicDecoder()
        var compared = 0
        var withAlpha = 0
        println("%-8s %-10s %8s %8s %8s %9s".format("archive", "size", "colour", "solid", "clear", "pixels"))
        for (archive in cache.archiveIds(Index.LOADING_GRAPHICS)) {
            val group = cache.data(Index.LOADING_GRAPHICS, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                continue
            }
            val raw = cache.data(Index.LOADING_GRAPHICS_RAW, archive) ?: continue
            val definition = GraphicDefinition(archive)
            decoder.readLoop(definition, BufferReader(raw))
            val frames = definition.graphics ?: continue
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
            assertTrue(
                colour < COLOUR_TOLERANCE,
                "archive $archive decodes $colour levels away from index 34's copy of the same picture"
            )
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
    fun `a re-saved loading screen still joins into a four component file the client decodes`() {
        val cache = cache() ?: return
        val (archive, group) = largest(cache) ?: return
        val file = JagexJpeg.editable(group)
        val original = JagexJpeg.decode(group)

        val edited = ImageIO.read(ByteArrayInputStream(file))
        for (y in 0 until HOLE) {
            for (x in 0 until HOLE) {
                edited.setRGB(x, y, RED)
            }
        }
        val resaved = resave(edited, 0.95f)
        assertTrue(markers(resaved).contains(APP0), "the re-saved file should carry the JFIF segment ImageIO writes")
        assertTrue(markers(resaved).none { it == APP11 }, "ImageIO was expected to strip the alpha segment")

        val joined = JagexJpeg.archive(JagexJpeg.embed(resaved, JagexJpeg.alphaOf(file)!!))
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
        println("archive $archive re-saved and rejoined: ${joined.size} bytes, drift ${drift(original, decoded)}")
        assertTrue(drift(original, decoded) < COLOUR_TOLERANCE, "a re-saved file drifted more than a re-encode costs")
    }

    @Test
    fun `a loading screen whose alpha segment was stripped packs as an opaque image`() {
        val cache = cache() ?: return
        val (archive, group) = largest(cache) ?: return
        val stripped = resave(ImageIO.read(ByteArrayInputStream(JagexJpeg.editable(group))), 0.95f)
        assertEquals(null, JagexJpeg.alphaOf(stripped), "the re-saved file was expected to have no alpha")
        val joined = JagexJpeg.archive(stripped)
        val rebuilt = BaselineJpeg.read(joined)
        assertEquals(4, rebuilt.components.size, "archive $archive did not rebuild into four components")
        val decoded = JagexJpeg.decode(joined)
        var opaque = 0
        for (y in 0 until decoded.height) {
            for (x in 0 until decoded.width) {
                if (decoded.getRGB(x, y) ushr 24 == 0xff) {
                    opaque++
                }
            }
        }
        println("archive $archive with its alpha stripped: ${joined.size} bytes, $opaque of ${decoded.width * decoded.height} pixels opaque")
        assertEquals(decoded.width * decoded.height, opaque, "a synthesised alpha must be opaque everywhere")
        assertTrue(drift(JagexJpeg.decode(group), decoded) < COLOUR_TOLERANCE, "the picture drifted more than a re-encode costs")
    }

    @Test
    fun `an RGBA PNG stands in for a loading screen`() {
        val cache = cache() ?: return
        val (archive, group) = largest(cache) ?: return
        val original = JagexJpeg.decode(group)
        val painted = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB)
        painted.graphics.drawImage(original, 0, 0, null)
        for (y in 0 until HOLE) {
            for (x in 0 until HOLE) {
                painted.setRGB(x, y, 0)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(painted, "png", out)
        val encoded = JagexJpeg.archive(ImageIO.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(4, BaselineJpeg.read(encoded).components.size, "archive $archive's PNG did not encode four components")
        val decoded = JagexJpeg.decode(encoded)
        assertEquals(original.width, decoded.width)
        assertTrue(
            decoded.getRGB(HOLE / 2, HOLE / 2) ushr 24 < EDIT_TOLERANCE,
            "the transparent block painted in the PNG did not survive"
        )
        assertTrue(
            decoded.getRGB(decoded.width - 1, decoded.height - 1) ushr 24 > 0xff - EDIT_TOLERANCE,
            "the rest of the picture stopped being opaque"
        )
        println("archive $archive through an RGBA PNG: ${encoded.size} bytes")
    }

    @Test
    fun `every loading screen is written out where somebody can look at it`() {
        val cache = cache() ?: return
        val directory = File(OUTPUT)
        directory.mkdirs()
        var written = 0
        for (archive in cache.archiveIds(Index.LOADING_GRAPHICS)) {
            val group = cache.data(Index.LOADING_GRAPHICS, archive) ?: continue
            if (!JagexJpeg.isJpeg(group)) {
                continue
            }
            val file = JagexJpeg.editable(group)
            File(directory, "$archive${GraphicCodec.JPEG_EXTENSION}").writeBytes(file)
            val preview = File(directory, "loading-$archive.png")
            ImageIO.write(ImageIO.read(ByteArrayInputStream(file)), "png", preview)
            ImageIO.write(JagexJpeg.decode(group), "png", File(directory, "loading-$archive-rgba.png"))
            assertTrue(preview.length() > 0, "nothing was written to ${preview.absolutePath}")
            if (archive == PROOF) {
                val proof = File(PROOFS, "loading-proof.png")
                proof.parentFile.mkdirs()
                proof.writeBytes(preview.readBytes())
                println("wrote loading_graphics/$PROOF${GraphicCodec.JPEG_EXTENSION} to ${proof.absolutePath}")
            }
            written++
        }
        println("wrote $written loading screens to ${directory.absolutePath}")
    }

    @Test
    fun `a JPEG archive maps its file back to its archive`() {
        assertEquals(3770, GraphicCodec.archiveOf("3770${GraphicCodec.JPEG_EXTENSION}"))
        assertEquals(3770, GraphicCodec.archiveOf("3770${GraphicCodec.EXTENSION}"))
        assertEquals(null, GraphicCodec.archiveOf("3770.dat"))
    }

    private fun drift(was: BufferedImage, now: BufferedImage): Double {
        var error = 0L
        var pixels = 0L
        for (y in 0 until was.height) {
            for (x in 0 until was.width) {
                if (x < HOLE * 2 && y < HOLE * 2) {
                    continue
                }
                val before = was.getRGB(x, y)
                val after = now.getRGB(x, y)
                error += abs(((before shr 16) and 0xff) - ((after shr 16) and 0xff)).toLong()
                error += abs(((before shr 8) and 0xff) - ((after shr 8) and 0xff)).toLong()
                error += abs((before and 0xff) - (after and 0xff)).toLong()
                pixels++
            }
        }
        return error / (pixels * 3.0)
    }

    private fun largest(cache: Cache): Pair<Int, ByteArray>? {
        var best: Pair<Int, ByteArray>? = null
        var area = 0
        for (archive in cache.archiveIds(Index.LOADING_GRAPHICS)) {
            val group = cache.data(Index.LOADING_GRAPHICS, archive) ?: continue
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

    private fun resave(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
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
        private const val APP11 = 0xeb
        private const val COM = 0xfe
        private val APPLICATION = 0xe0..0xef

        private const val COLOUR_TOLERANCE = 16.0

        private const val SOLID = 200.0

        private const val CLEAR = 64.0

        private const val EDIT_TOLERANCE = 24

        private const val HOLE = 32

        private const val RED = 0xffff0000.toInt()

        private const val OUTPUT = "build/loading-screens"

        private const val PROOF = 3770

        private const val PROOFS =
            "/tmp/claude-1000/-home-trent-darkan-server/7342d152-879a-4df7-9110-0ce208e488a1/scratchpad"
    }
}
