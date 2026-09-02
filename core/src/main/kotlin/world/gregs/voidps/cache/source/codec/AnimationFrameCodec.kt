package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Index 0 as one JSON file per animation frame, `anims/<archive>/<frame>.json`.
 *
 * A [DefinitionJsonCodec] in everything but one respect, which is the respect index 0 exists for: its
 * groups are split into **three chunks**, and joining a group back as one chunk - which is what every
 * other multi-file index uses and what `ArchiveGroup.join` does by default - produces a different, and
 * equally valid, group. The layout therefore has to be reproduced, and this codec reproduces it rather
 * than recording it, because for index 0 the split is exactly the frame format's own three sections:
 *
 * | chunk | holds | length |
 * |---|---|---|
 * | 0 | the header: a byte, the base id and the transform count | always 4 |
 * | 1 | the flag byte per transform | the transform count |
 * | 2 | the transform values, as signed smarts | whatever is left |
 *
 * So nothing about the layout has to go in `index.json` and nothing about it can go stale: the group is
 * a function of the frames, the same way every other codec's is. A single file archive has no chunk table
 * at all - eleven of index 0's 4,085 archives are like that - and [ArchiveGroup.join] writes none.
 *
 * The pristine fallback works exactly as [DefinitionJsonCodec]'s does, file by file rather than archive
 * by archive: a frame whose JSON does not re-encode to the shipped bytes keeps them in a sidecar guarded
 * by the SHA-256 of its JSON. No 727 frame needs one.
 */
object AnimationFrameCodec : SourceCodec {

    override val id: String
        get() = "json-animframe"

    /** `<archive>/<file>.json`, and the definition id is `archive shl 16 or file`. */
    private val layout = DefinitionLayout.Nested(AnimationFrameDecoder.BITS)

    private val decoder = AnimationFrameDecoder()

    private val encoder = AnimationFrameEncoder()

    private val json by lazy { DefinitionJson.of(AnimationFrameDefinition::class.java) }

    /** False, so the unpacker packs the group back and proves the chunk layout as well as the bytes. */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val name = layout.file(archive.archive, file)
            val text = json.write(decoded(archive.archive, file, data))
            files.add(SourceFile(name, text))
            if (!compile(text, archive.archive, file).contentEquals(data)) {
                files.add(SourceFile(SourceFiles.pristine(name), data))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = layout.fileIds(directory, archive)
        check(ids.isNotEmpty()) { "Frame set $archive has no frames in ${directory.toAbsolutePath()}." }
        val files = ArrayList<ByteArray>(ids.size)
        for (file in ids) {
            files.add(bytes(directory, archive, file, metadata))
        }
        return PackedArchive(ArchiveGroup.join(files, chunks(files)), ids)
    }

    override fun archiveOf(path: String): Int? = layout.archiveOf(SourceFiles.guarded(path) ?: path)

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>()
        for (file in layout.fileIds(directory, archive)) {
            val path = directory.resolve(layout.file(archive, file))
            files.add(path)
            val sidecar = SourceFiles.pristine(path)
            if (Files.isRegularFile(sidecar)) {
                files.add(sidecar)
            }
        }
        return files
    }

    /**
     * The three chunk split every multi-frame group in index 0 uses, taken from the frames themselves.
     *
     * Null for a single frame group, which carries no chunk table at all.
     */
    private fun chunks(files: List<ByteArray>): List<IntArray>? {
        if (files.size == 1) {
            return null
        }
        val header = IntArray(files.size)
        val flags = IntArray(files.size)
        val values = IntArray(files.size)
        for (index in files.indices) {
            val frame = files[index]
            check(frame.size >= HEADER) { "A frame of ${frame.size} bytes has no $HEADER byte header." }
            val declared = frame[COUNT].toInt() and 0xff
            // A declared 0 with a payload behind it is 256; see AnimationFrameDefinition.WRAPPED_COUNT.
            val count = if (declared == 0 && frame.size > HEADER) {
                AnimationFrameDefinition.WRAPPED_COUNT
            } else {
                declared
            }
            check(frame.size >= HEADER + count) {
                "A frame of ${frame.size} bytes cannot hold $count flag bytes."
            }
            header[index] = HEADER
            flags[index] = count
            values[index] = frame.size - HEADER - count
        }
        return listOf(header, flags, values)
    }

    /** One frame's bytes: the pristine copy while its JSON still hashes to the recorded guard. */
    private fun bytes(directory: Path, archive: Int, file: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(layout.file(archive, file))
        if (!Files.isRegularFile(path)) {
            throw IOException("Frame set $archive frame $file has no JSON at ${path.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[file]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        return compile(text, archive, file)
    }

    private fun decoded(archive: Int, file: Int, data: ByteArray): AnimationFrameDefinition {
        val definition = AnimationFrameDefinition(layout.definition(archive, file))
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    private fun compile(text: ByteArray, archive: Int, file: Int): ByteArray {
        val definition = AnimationFrameDefinition(layout.definition(archive, file))
        json.read(text, definition)
        val writer = BufferWriter(HEADER + definition.flags.size + definition.values.size * 2)
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    /** A frame's header - a byte, the base id and the transform count - which is chunk 0. */
    private const val HEADER = 4

    /** Where the transform count sits in that header. */
    private const val COUNT = 3
}
