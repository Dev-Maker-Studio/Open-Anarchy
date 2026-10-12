package world.gregs.voidps.cache.source.codec

import org.darkan.core.Logger.logWarn
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object AnimationFrameCodec : SourceCodec {

    const val EXTENSION = ".glb"

    override val id: String
        get() = "gltf-anim"

    @Volatile
    var cache: () -> Cache = { Cache.get() }

    override fun exact(archive: Int): Boolean = false

    override fun fileIdsFromMetadata(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val glb = glb(archive)
        val name = "${archive.archive}$EXTENSION"
        val rebuilt = try {
            encode(AnimationGltf.read(glb, archive.archive))
        } catch (e: Exception) {
            logWarn("Frame set ${archive.archive} did not survive its glb: ${e.message}")
            null
        }
        if (rebuilt != null && rebuilt.contentEquals(archive.group)) {
            return UnpackedArchive(listOf(SourceFile(name, glb)))
        }
        return UnpackedArchive(
            listOf(SourceFile(name, glb), SourceFile(SourceFiles.pristine(name), archive.group)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(glb))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            throw IOException("Frame set $archive has no glb at ${path.toAbsolutePath()}.")
        }
        val glb = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine.values.firstOrNull()
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(glb) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), AnimationGltf.read(glb, archive).fileIds())
            }
            Files.delete(sidecar)
        }
        val set = AnimationGltf.read(glb, archive)
        check(set.frames.isNotEmpty()) { "Frame set $archive has no frames in ${path.toAbsolutePath()}." }
        return PackedArchive(encode(set), set.fileIds())
    }

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        if (!editable.endsWith(EXTENSION) || editable.contains('/')) {
            return null
        }
        return editable.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(path)
        return if (Files.isRegularFile(sidecar)) listOf(path, sidecar) else listOf(path)
    }

    fun reset() {
        synchronized(this) {
            bases.clear()
            resolved = null
            cache = { Cache.get() }
        }
    }

    internal fun glb(archive: SourceArchive): ByteArray {
        val set = decode(archive)
        return AnimationGltf.write(set, base(set.baseId))
    }

    private fun decode(archive: SourceArchive): AnimationFrameSet {
        val frames = ArrayList<AnimationFrameDefinition>(archive.files.size)
        for ((position, file) in archive.fileIds.withIndex()) {
            frames.add(decoded(archive.archive, file, archive.files[position]))
        }
        val chunks = if (archive.chunks.isEmpty()) {
            if (frames.size == 1) 1 else AnimationGltf.CHUNKS
        } else {
            archive.chunks.size
        }
        return AnimationFrameSet(
            archive = archive.archive,
            baseId = frames.firstOrNull()?.baseId ?: -1,
            frames = frames,
            chunks = chunks,
            header = AnimationGltf.HEADER
        )
    }

    internal fun encode(set: AnimationFrameSet): ByteArray {
        val files = ArrayList<ByteArray>(set.frames.size)
        for (frame in set.frames) {
            files.add(encoded(frame))
        }
        return ArchiveGroup.join(files, chunks(set, files))
    }

    private fun chunks(set: AnimationFrameSet, files: List<ByteArray>): List<IntArray>? {
        if (set.chunks <= 1 || files.size == 1) {
            return null
        }
        check(set.chunks == AnimationGltf.CHUNKS) {
            "Frame set ${set.archive} says its group is ${set.chunks} chunks; index 0 stores " +
                "${AnimationGltf.CHUNKS}, whose lengths are the frame format's own three sections."
        }
        val header = IntArray(files.size)
        val flags = IntArray(files.size)
        val values = IntArray(files.size)
        for (index in files.indices) {
            val count = set.frames[index].flags.size
            val rest = files[index].size - set.header - count
            check(rest >= 0) {
                "Frame set ${set.archive} frame ${set.frames[index].id} is ${files[index].size} bytes, " +
                    "which cannot hold a ${set.header} byte header and $count flag bytes."
            }
            header[index] = set.header
            flags[index] = count
            values[index] = rest
        }
        return listOf(header, flags, values)
    }

    private fun decoded(archive: Int, file: Int, data: ByteArray): AnimationFrameDefinition {
        val definition = AnimationFrameDefinition((archive shl AnimationFrameDecoder.BITS) or file)
        decoder.readLoop(definition, BufferReader(data))
        return definition
    }

    private fun encoded(frame: AnimationFrameDefinition): ByteArray {
        val writer = BufferWriter(AnimationGltf.HEADER + frame.flags.size + frame.values.size * 2)
        with(encoder) { writer.encode(frame) }
        return writer.toArray()
    }

    private fun base(id: Int): AnimationBaseDefinition? {
        if (id < 0) {
            return null
        }
        return bases.computeIfAbsent(id) { key -> skeleton(key) }
    }

    private fun skeleton(id: Int): AnimationBaseDefinition {
        val definition = AnimationBaseDefinition(id)
        val data = skeletons().data(Index.ANIMATION_SKELETONS, id, 0)
        if (data == null) {
            logWarn("Animation base $id is not in the cache; its frame sets get untyped nodes.")
        } else {
            baseDecoder.readLoop(definition, BufferReader(data))
        }
        return definition
    }

    private fun skeletons(): Cache = resolved ?: synchronized(this) {
        resolved ?: cache().also { resolved = it }
    }

    @Volatile
    private var resolved: Cache? = null

    private val bases = ConcurrentHashMap<Int, AnimationBaseDefinition>()

    private val decoder = AnimationFrameDecoder()

    private val encoder = AnimationFrameEncoder()

    private val baseDecoder = AnimationBaseDecoder()
}
