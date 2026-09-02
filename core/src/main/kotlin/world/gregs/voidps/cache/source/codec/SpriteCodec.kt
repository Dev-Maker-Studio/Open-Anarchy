package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.IndexedSprite
import world.gregs.voidps.cache.definition.data.SpriteDefinition
import world.gregs.voidps.cache.definition.decoder.SpriteDecoder
import world.gregs.voidps.cache.definition.encoder.SpriteEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A sprite index as standard images: one indexed colour PNG per frame, plus the little JSON the
 * PNG cannot carry.
 *
 * ```
 * sprites/1234/sprite.json     the palette, the canvas, and one entry per frame
 * sprites/1234/0.png           frame 0's palette indices, indexed colour
 * sprites/1234/0.alpha.png     frame 0's alpha plane, when the frame has one
 * ```
 *
 * **What lives where.** The PNG owns the pixels and nothing else; `sprite.json` owns the palette,
 * the group canvas and each frame's placement and format byte. The palette is written into every
 * frame's `PLTE` too, so the images display correctly in any viewer, but the JSON is what is read
 * back - a group whose frames are all zero sized (2,498 of the 727 cache's frames are, and some
 * groups are nothing but) has no PNG for a palette to live in, and one palette in one place cannot
 * disagree with itself. Recolouring a sprite is therefore an edit to `sprite.json`; repainting one
 * is an edit to the PNG.
 *
 * **JPEG archives are not sprites.** Index 32 holds 21 loading screen images as JPEG containers
 * rather than palette sprites - the client hands them to `Toolkit.createImage` - so they are
 * stored under their real extension, byte for byte, and never re-encoded. See
 * `docs/cache-source.md`, "loading sprites".
 */
object SpriteCodec : SourceCodec {

    /** The frame's palette indices. */
    const val EXTENSION = ".png"

    /** The frame's alpha plane, when its format byte says it has one. */
    const val ALPHA_EXTENSION = ".alpha$EXTENSION"

    /** The per group metadata the images cannot carry. */
    const val METADATA = "sprite.json"

    /** A JPEG archive, stored verbatim. */
    const val JPEG_EXTENSION = ".jpg"

    private val decoder = SpriteDecoder()

    private val encoder = SpriteEncoder()

    private val json = Json { ignoreUnknownKeys = true }

    override val id: String
        get() = "sprite-png"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    /**
     * False, so the unpacker packs every group back and compares it with the bytes it came from.
     * [unpack] has already installed a pristine sidecar for anything that did not survive, so this
     * is a second, independent proof rather than the only one.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; a sprite group is one."
        }
        val data = archive.files[0]
        if (jpeg(data)) {
            return UnpackedArchive(listOf(SourceFile("${archive.archive}$JPEG_EXTENSION", data)))
        }
        val definition = decode(archive.archive, data)
        val files = write(archive.archive, definition)
        if (compile(archive.archive, files).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        // The images stay the editable form and the shipped bytes ride along beside them, exactly
        // as a definition's JSON does when its encoder cannot reproduce the file.
        val guard = guard(files)
        return UnpackedArchive(
            files + SourceFile(SourceFiles.pristine("${archive.archive}/$METADATA"), data),
            mapOf(archive.fileIds[0] to guard)
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one sprite group but its metadata lists ${ids.size} files." }
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return PackedArchive(Files.readAllBytes(jpeg), ids)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isRegularFile(folder.resolve(METADATA))) {
            throw IOException("Archive $archive has no $METADATA in ${folder.toAbsolutePath()}.")
        }
        val files = read(folder, archive)
        val sidecar = SourceFiles.pristine(folder.resolve(METADATA))
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
        val separator = path.indexOf('/')
        if (separator < 0) {
            if (!path.endsWith(JPEG_EXTENSION)) {
                return null
            }
            return path.dropLast(JPEG_EXTENSION.length).toIntOrNull()
        }
        if (path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        return path.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val jpeg = directory.resolve("$archive$JPEG_EXTENSION")
        if (Files.isRegularFile(jpeg)) {
            return listOf(jpeg)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    /** Whether [data] is a JPEG container rather than a palette sprite group. */
    fun jpeg(data: ByteArray): Boolean =
        data.size > 2 && data[0] == JPEG_MAGIC_0 && data[1] == JPEG_MAGIC_1

    private fun decode(archive: Int, data: ByteArray): SpriteDefinition {
        val definition = SpriteDefinition(archive)
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    /** [definition] as the files it is stored in: the metadata first, then a frame at a time. */
    private fun write(archive: Int, definition: SpriteDefinition): List<SourceFile> {
        val sprites = definition.sprites ?: error("Sprite $archive decoded to no frames.")
        val files = ArrayList<SourceFile>(sprites.size * 2 + 1)
        files.add(SourceFile("$archive/$METADATA", metadata(definition, sprites)))
        for (frame in sprites.indices) {
            val sprite = sprites[frame]
            if (sprite.width <= 0 || sprite.height <= 0) {
                // An empty glyph: the frame is a format byte and a placement, with no image in it.
                continue
            }
            val alpha = sprite.flags and ALPHA_FLAG != 0
            files.add(
                SourceFile(
                    "$archive/$frame$EXTENSION",
                    SpriteImage.write(sprite, definition.rawPalette, transparent = !alpha)
                )
            )
            if (alpha) {
                val plane = sprite.alpha ?: ByteArray(sprite.width * sprite.height) { OPAQUE }
                files.add(SourceFile("$archive/$frame$ALPHA_EXTENSION", SpriteImage.writeAlpha(sprite, plane)))
            }
        }
        return files
    }

    /** `sprite.json`'s exact text; written by hand for the same reason `index.json` is. */
    private fun metadata(definition: SpriteDefinition, sprites: Array<IndexedSprite>): ByteArray {
        val out = StringBuilder(sprites.size * 64)
        out.append("{\n")
        out.append("  \"id\": ").append(definition.id).append(",\n")
        out.append("  \"width\": ").append(definition.width).append(",\n")
        out.append("  \"height\": ").append(definition.height).append(",\n")
        if (definition.paletteZeroEntry) {
            out.append("  \"paletteZeroEntry\": true,\n")
        }
        out.append("  \"palette\": [\n")
        for (index in definition.rawPalette.indices) {
            out.append("    ").append(definition.rawPalette[index])
            if (index != definition.rawPalette.size - 1) {
                out.append(',')
            }
            out.append('\n')
        }
        out.append("  ],\n")
        out.append("  \"frames\": [\n")
        for (index in sprites.indices) {
            val sprite = sprites[index]
            out.append("    {\"minX\": ").append(sprite.offsetX)
            out.append(", \"minY\": ").append(sprite.offsetY)
            out.append(", \"width\": ").append(sprite.width)
            out.append(", \"height\": ").append(sprite.height)
            out.append(", \"flags\": ").append(sprite.flags).append('}')
            if (index != sprites.size - 1) {
                out.append(',')
            }
            out.append('\n')
        }
        out.append("  ]\n")
        out.append("}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** The archive's files as they are on disk, in the order [write] produced them. */
    private fun read(folder: Path, archive: Int): List<SourceFile> {
        val files = ArrayList<SourceFile>()
        val metadata = Files.readAllBytes(folder.resolve(METADATA))
        files.add(SourceFile("$archive/$METADATA", metadata))
        val frames = json.parseToJsonElement(String(metadata, Charsets.UTF_8)).jsonObject["frames"]
            ?: throw IOException("Archive $archive's $METADATA has no frames.")
        for (frame in frames.jsonArray.indices) {
            for (name in listOf("$frame$EXTENSION", "$frame$ALPHA_EXTENSION")) {
                val path = folder.resolve(name)
                if (Files.isRegularFile(path)) {
                    files.add(SourceFile("$archive/$name", Files.readAllBytes(path)))
                }
            }
        }
        return files
    }

    /** The files back into a group, which is what a pack emits and what [unpack] checks itself with. */
    private fun compile(archive: Int, files: List<SourceFile>): ByteArray {
        val images = files.associateBy { it.path.substringAfterLast('/') }
        val metadata = images[METADATA] ?: throw IOException("Archive $archive has no $METADATA.")
        val root = json.parseToJsonElement(String(metadata.bytes, Charsets.UTF_8)).jsonObject
        val definition = SpriteDefinition(archive)
        definition.width = root.getValue("width").jsonPrimitive.int
        definition.height = root.getValue("height").jsonPrimitive.int
        definition.paletteZeroEntry = root["paletteZeroEntry"]?.jsonPrimitive?.boolean ?: false
        definition.rawPalette = root.getValue("palette").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val frames = root.getValue("frames").jsonArray
        val sprites = Array(frames.size) { frame ->
            val entry = frames[frame].jsonObject
            val sprite = IndexedSprite(
                offsetX = entry.getValue("minX").jsonPrimitive.int,
                offsetY = entry.getValue("minY").jsonPrimitive.int,
                width = entry.getValue("width").jsonPrimitive.int,
                height = entry.getValue("height").jsonPrimitive.int
            )
            sprite.flags = entry.getValue("flags").jsonPrimitive.int
            sprite.palette = definition.rawPalette
            sprite.deltaWidth = definition.width - sprite.width - sprite.offsetX
            sprite.deltaHeight = definition.height - sprite.height - sprite.offsetY
            sprite.raster = pixels(archive, images, frame, sprite, EXTENSION)
            if (sprite.flags and ALPHA_FLAG != 0) {
                sprite.alpha = pixels(archive, images, frame, sprite, ALPHA_EXTENSION)
            }
            sprite
        }
        definition.sprites = sprites
        val writer = BufferWriter(capacity(definition, sprites))
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    private fun pixels(
        archive: Int,
        images: Map<String, SourceFile>,
        frame: Int,
        sprite: IndexedSprite,
        extension: String
    ): ByteArray {
        val area = sprite.width * sprite.height
        if (area == 0) {
            return ByteArray(0)
        }
        val name = "$frame$extension"
        val image = images[name] ?: throw IOException("Archive $archive frame $frame has no $name.")
        return SpriteImage.read(image.bytes, sprite.width, sprite.height, "Archive $archive's $name")
    }

    /** Exactly what the group will be: pixels, planes, palette and the fixed trailer. */
    private fun capacity(definition: SpriteDefinition, sprites: Array<IndexedSprite>): Int {
        var size = TRAILER + sprites.size * FRAME_TRAILER
        size += definition.rawPalette.size * COLOUR
        for (sprite in sprites) {
            val area = sprite.width * sprite.height
            size += 1 + area + if (sprite.flags and ALPHA_FLAG != 0) area else 0
        }
        return size
    }

    /**
     * The guard `index.json` records for a pristine sidecar: every editable file of the group,
     * named and hashed together, because the group is a directory rather than one file.
     */
    private fun guard(files: List<SourceFile>): String {
        val out = StringBuilder()
        for (file in files.sortedBy { it.path }) {
            out.append(file.path).append(':').append(SourceFiles.sha256(file.bytes)).append('\n')
        }
        return SourceFiles.sha256(out.toString().toByteArray(Charsets.UTF_8))
    }

    /** The frame carries an alpha plane after its pixels. */
    private const val ALPHA_FLAG = 0x2

    private const val OPAQUE = 0xff.toByte()

    /** The canvas size, the palette size byte and the frame count. */
    private const val TRAILER = 7

    /** Four shorts per frame: `minX`, `minY`, `width`, `height`. */
    private const val FRAME_TRAILER = 8

    private const val COLOUR = 3

    private const val JPEG_MAGIC_0 = 0xff.toByte()

    private const val JPEG_MAGIC_1 = 0xd8.toByte()
}
