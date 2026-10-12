package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class BinaryArchiveCodec(
    override val id: String,
    val extension: String
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        return UnpackedArchive(listOf(SourceFile("${archive.archive}$extension", archive.files[0])))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$extension")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no data file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(extension) || path.contains('/')) {
            return null
        }
        return path.dropLast(extension.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$extension")
        return if (Files.exists(file)) listOf(file) else emptyList()
    }
}
