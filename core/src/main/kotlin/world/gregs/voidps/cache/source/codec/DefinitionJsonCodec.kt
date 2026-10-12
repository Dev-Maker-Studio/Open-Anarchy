package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path

class DefinitionJsonCodec<T : Definition>(
    override val id: String,
    private val layout: DefinitionLayout,
    private val create: (Int) -> T,
    private val decode: (T, ByteArray) -> Unit,
    private val encode: (T, Writer) -> Unit
) : SourceCodec {

    private val json by lazy { DefinitionJson.of(create(0)::class.java) }

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size * 2)
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
        check(ids.isNotEmpty()) { "Archive $archive has no definition files in ${directory.toAbsolutePath()}." }
        val files = ArrayList<ByteArray>(ids.size)
        for (file in ids) {
            files.add(bytes(directory, archive, file, metadata))
        }
        return PackedArchive(ArchiveGroup.join(files), ids)
    }

    override fun archiveOf(path: String): Int? = layout.archiveOf(editable(path))

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

    private fun bytes(directory: Path, archive: Int, file: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(layout.file(archive, file))
        if (!Files.isRegularFile(path)) {
            throw IOException("Archive $archive file $file has no JSON at ${path.toAbsolutePath()}.")
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

    private fun decoded(archive: Int, file: Int, data: ByteArray): T {
        val definition = create(layout.definition(archive, file))
        decode(definition, data)
        return definition
    }

    private fun compile(text: ByteArray, archive: Int, file: Int): ByteArray {
        val definition = create(layout.definition(archive, file))
        json.read(text, definition)
        var capacity = CAPACITY
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                encode(definition, writer)
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "Definition ${definition.id} does not encode into $LIMIT bytes." }
            }
        }
    }

    private fun editable(path: String): String = SourceFiles.guarded(path) ?: path

    private companion object {
        private const val CAPACITY = 16 * 1024

        private const val LIMIT = 8 * 1024 * 1024
    }
}
