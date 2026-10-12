package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TranscodedArchiveCodec(
    override val id: String,
    val extension: String,
    private val decode: (ByteArray) -> ByteArray,
    private val encode: (ByteArray) -> ByteArray
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        val data = archive.files[0]
        val path = "${archive.archive}$extension"
        val standard = decode(data)
        val files = ArrayList<SourceFile>(2)
        files.add(SourceFile(path, standard))
        if (encode(standard).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        files.add(SourceFile(SourceFiles.pristine(path), data))
        return UnpackedArchive(files, mapOf(archive.fileIds[0] to SourceFiles.sha256(standard)))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$extension")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        val standard = Files.readAllBytes(file)
        val sidecar = SourceFiles.pristine(file)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(standard) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(encode(standard), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (path.contains('/')) {
            return null
        }
        val name = SourceFiles.guarded(path) ?: path
        if (!name.endsWith(extension)) {
            return null
        }
        return name.dropLast(extension.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$extension")
        if (!Files.exists(file)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(file)
        return if (Files.exists(sidecar)) listOf(file, sidecar) else listOf(file)
    }
}
