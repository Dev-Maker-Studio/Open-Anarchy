package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.encoder.GraphicEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.name

object GraphicCodec : SourceCodec {

    const val EXTENSION = ".png"

    const val JPEG_EXTENSION = ".jpg"

    const val PALETTE_CHUNK = "rsPL"

    const val FRAME_CHUNK = "rsFR"

    const val INDEX_CHUNK = "rsIX"

    const val PRISTINE = "group"

    private val decoder = GraphicDecoder()

    private val encoder = GraphicEncoder()

    override val id: String
        get() = "graphic-png"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; a graphic group is one."
        }
        val data = archive.files[0]
        if (jpeg(data)) {
            return UnpackedArchive(
                listOf(SourceFile("${archive.archive}$JPEG_EXTENSION", JagexJpeg.editable(data)))
            )
        }
        val definition = decode(archive.archive, data)
        val files = write(archive.archive, definition)
        if (compile(archive.archive, files).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        val guard = guard(files)
        return UnpackedArchive(
            files + SourceFile(SourceFiles.pristine("${archive.archive}/$PRISTINE"), data),
            mapOf(archive.fileIds[0] to guard)
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one graphic group but its metadata lists ${ids.size} files." }
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return PackedArchive(JagexJpeg.archive(Files.readAllBytes(jpeg)), ids)
        }
        val painted = directory.resolve("$archive$EXTENSION")
        if (Files.isRegularFile(painted)) {
            return PackedArchive(JagexJpeg.archive(image(painted)), ids)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            throw IOException(
                "Archive $archive has neither a $archive$JPEG_EXTENSION nor a $archive/ in ${directory.toAbsolutePath()}."
            )
        }
        val files = read(folder, archive)
        val sidecar = SourceFiles.pristine(folder.resolve(PRISTINE))
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (guard(files) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(archive, files), ids)
    }

    override fun archiveOf(path: String): Int? {
        val name = SourceFiles.guarded(path) ?: path
        val separator = name.indexOf('/')
        if (separator < 0) {
            for (extension in listOf(JPEG_EXTENSION, EXTENSION)) {
                if (name.endsWith(extension)) {
                    return name.dropLast(extension.length).toIntOrNull()
                }
            }
            return null
        }
        if (name.indexOf('/', separator + 1) >= 0) {
            return null
        }
        return name.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return listOf(jpeg)
        }
        val painted = directory.resolve("$archive$EXTENSION")
        if (Files.isRegularFile(painted)) {
            return listOf(painted)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    fun jpeg(data: ByteArray): Boolean = JagexJpeg.isJpeg(data)

    private fun decode(archive: Int, data: ByteArray): GraphicDefinition {
        val definition = GraphicDefinition(archive)
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    private fun write(archive: Int, definition: GraphicDefinition): List<SourceFile> {
        val graphics = definition.graphics ?: error("Graphic $archive decoded to no frames.")
        val files = ArrayList<SourceFile>(graphics.size)
        for (frame in graphics.indices) {
            files.add(
                SourceFile(
                    "$archive/$frame$EXTENSION",
                    GraphicImage.write(archive, frame, definition, graphics[frame])
                )
            )
        }
        return files
    }

    private fun read(folder: Path, archive: Int): List<SourceFile> {
        val frames = HashMap<Int, Path>()
        Files.list(folder).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val name = path.name
                if (SourceFiles.isPristine(name)) {
                    continue
                }
                if (!name.endsWith(EXTENSION)) {
                    throw IOException("Archive $archive has a $name; a graphic group is $EXTENSION frames only.")
                }
                val frame = name.dropLast(EXTENSION.length).toIntOrNull()
                    ?: throw IOException("Archive $archive has a $name, whose name is not a frame number.")
                frames[frame] = path
            }
        }
        if (frames.isEmpty()) {
            throw IOException("Archive $archive has no frames in ${folder.toAbsolutePath()}.")
        }
        val files = ArrayList<SourceFile>(frames.size)
        for (frame in 0 until frames.size) {
            val path = frames[frame]
                ?: throw IOException("Archive $archive has ${frames.size} frames but no $frame$EXTENSION.")
            files.add(SourceFile("$archive/$frame$EXTENSION", Files.readAllBytes(path)))
        }
        return files
    }

    private fun compile(archive: Int, files: List<SourceFile>): ByteArray {
        if (files.isEmpty()) {
            throw IOException("Archive $archive has no frames.")
        }
        val images = files.map { GraphicImage.read(it.bytes, "Archive $archive's ${it.path.substringAfterLast('/')}") }
        val width = images[0].width
        val height = images[0].height
        for (frame in images.indices) {
            if (images[frame].width != width || images[frame].height != height) {
                throw IOException(
                    "Archive $archive frame $frame is ${images[frame].width}x${images[frame].height}" +
                        " but frame 0 is ${width}x$height; every frame of a group is its canvas."
                )
            }
        }
        val declared = images.firstNotNullOfOrNull { it.frame }
        val canvasWidth = declared?.canvasWidth ?: width
        val canvasHeight = declared?.canvasHeight ?: height
        val boxes = Array(images.size) { box(images[it]) }
        val colours = Colours(archive, images.firstNotNullOfOrNull { it.palette }?.entries)
        val graphics = Array(images.size) { frame -> graphic(images[frame], boxes[frame], colours) }
        val definition = GraphicDefinition(archive)
        definition.width = canvasWidth
        definition.height = canvasHeight
        definition.paletteZeroEntry = images.firstNotNullOfOrNull { it.palette }?.zeroEntry ?: false
        definition.rawPalette = colours.entries()
        val palette = GraphicImage.normalise(definition.rawPalette)
        for (graphic in graphics) {
            graphic.palette = palette
            graphic.deltaWidth = canvasWidth - graphic.width - graphic.offsetX
            graphic.deltaHeight = canvasHeight - graphic.height - graphic.offsetY
        }
        definition.graphics = graphics
        val writer = BufferWriter(capacity(definition, graphics))
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    private fun graphic(image: GraphicImage.Image, box: Box, colours: Colours): IndexedGraphic {
        val graphic = IndexedGraphic(box.minX, box.minY, box.width, box.height)
        val area = box.width * box.height
        val alpha = ByteArray(area)
        var partial = false
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val opacity = image.pixels[(box.minX + x) + (box.minY + y) * image.width] ushr 24
                alpha[x + y * box.width] = opacity.toByte()
                if (opacity != 0 && opacity != OPAQUE) {
                    partial = true
                }
            }
        }
        val kind = if (partial) GraphicImage.PLANE else image.frame?.alpha ?: GraphicImage.NO_PLANE
        val plane = if (kind == GraphicImage.PLANE) alpha else null
        graphic.raster = ByteArray(area)
        val raster = image.indices
        if (raster != null && raster.width == box.width && raster.height == box.height &&
            agrees(raster.values, image, box, colours, plane)
        ) {
            raster.values.copyInto(graphic.raster)
        } else {
            derive(graphic.raster, image, box, colours)
        }
        graphic.flags = ((image.frame?.flags ?: 0) and ALPHA_FLAG.inv()) or
            if (kind == GraphicImage.NO_PLANE) 0 else ALPHA_FLAG
        graphic.alpha = plane
        return graphic
    }

    private fun derive(raster: ByteArray, image: GraphicImage.Image, box: Box, colours: Colours) {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                raster[x + y * box.width] =
                    if (pixel ushr 24 == 0) 0 else colours.index(pixel and RGB).toByte()
            }
        }
    }

    private fun agrees(
        raster: ByteArray,
        image: GraphicImage.Image,
        box: Box,
        colours: Colours,
        plane: ByteArray?
    ): Boolean {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val offset = x + y * box.width
                val entry = raster[offset].toInt() and 0xff
                if (entry >= colours.size) {
                    return false
                }
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                val opacity = pixel ushr 24
                val expected = if (plane != null) {
                    plane[offset].toInt() and 0xff
                } else if (entry == 0) {
                    0
                } else {
                    OPAQUE
                }
                if (expected != opacity) {
                    return false
                }
                if (opacity != 0 && colours.colour(entry) != (pixel and RGB)) {
                    return false
                }
            }
        }
        return true
    }

    private fun box(image: GraphicImage.Image): Box {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.pixels[x + y * image.width] ushr 24 == 0) {
                    continue
                }
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        val tight = if (maxX < 0) Box(0, 0, 0, 0) else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
        val frame = image.frame ?: return tight
        val box = Box(frame.minX, frame.minY, frame.width, frame.height)
        if (box.minX + box.width > image.width || box.minY + box.height > image.height) {
            return tight
        }
        if (tight.width == 0 || tight.height == 0) {
            return box
        }
        val inside = tight.minX >= box.minX && tight.minY >= box.minY &&
            tight.minX + tight.width <= box.minX + box.width &&
            tight.minY + tight.height <= box.minY + box.height
        return if (inside) box else tight
    }

    private class Box(val minX: Int, val minY: Int, val width: Int, val height: Int)

    private class Colours(private val archive: Int, entries: IntArray?) {

        private val raw = ArrayList<Int>(LIMIT)

        private val colours = ArrayList<Int>(LIMIT)

        private val lookup = HashMap<Int, Int>(LIMIT * 2)

        init {
            val source = if (entries == null || entries.isEmpty()) intArrayOf(0) else entries
            if (source.size > LIMIT) {
                throw IOException("Archive $archive's palette has ${source.size} entries; the limit is $LIMIT.")
            }
            for (index in source.indices) {
                raw.add(source[index])
                colours.add(if (index > 0 && source[index] == 0) 1 else source[index])
            }
            for (index in 1 until colours.size) {
                lookup.putIfAbsent(colours[index], index)
            }
            for (index in 1 until raw.size) {
                lookup.putIfAbsent(raw[index], index)
            }
        }

        val size: Int
            get() = raw.size

        fun colour(index: Int): Int = colours[index]

        fun index(colour: Int): Int {
            val found = lookup[colour]
            if (found != null) {
                return found
            }
            if (raw.size >= LIMIT) {
                val nearest = nearest(colour)
                lookup[colour] = nearest
                return nearest
            }
            raw.add(colour)
            colours.add(if (colour == 0) 1 else colour)
            lookup[colour] = raw.size - 1
            return raw.size - 1
        }

        private fun nearest(colour: Int): Int {
            var best = 1
            var distance = Int.MAX_VALUE
            for (index in 1 until colours.size) {
                val red = ((colours[index] shr 16) and 0xff) - ((colour shr 16) and 0xff)
                val green = ((colours[index] shr 8) and 0xff) - ((colour shr 8) and 0xff)
                val blue = (colours[index] and 0xff) - (colour and 0xff)
                val error = red * red + green * green + blue * blue
                if (error < distance) {
                    distance = error
                    best = index
                }
            }
            return best
        }

        fun entries(): IntArray = raw.toIntArray()
    }

    private fun image(path: Path): BufferedImage =
        ImageIO.read(ByteArrayInputStream(Files.readAllBytes(path)))
            ?: throw IOException("${path.toAbsolutePath()} is not an image this build can read.")

    private fun capacity(definition: GraphicDefinition, graphics: Array<IndexedGraphic>): Int {
        var size = TRAILER + graphics.size * FRAME_TRAILER
        size += definition.rawPalette.size * COLOUR
        for (graphic in graphics) {
            val area = graphic.width * graphic.height
            size += 1 + area + if (graphic.flags and ALPHA_FLAG != 0) area else 0
        }
        return size
    }

    private fun guard(files: List<SourceFile>): String {
        val out = StringBuilder()
        for (file in files.sortedBy { it.path }) {
            out.append(file.path).append(':').append(SourceFiles.sha256(file.bytes)).append('\n')
        }
        return SourceFiles.sha256(out.toString().toByteArray(Charsets.UTF_8))
    }

    private const val ALPHA_FLAG = 0x2

    private const val OPAQUE = 0xff

    private const val RGB = 0xffffff

    private const val LIMIT = 256

    private const val TRAILER = 7

    private const val FRAME_TRAILER = 8

    private const val COLOUR = 3
}
