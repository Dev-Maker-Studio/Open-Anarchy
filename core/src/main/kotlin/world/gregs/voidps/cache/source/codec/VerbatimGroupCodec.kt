package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object VerbatimGroupCodec : SourceCodec {

    const val EXTENSION = ".grp"

    override val id: String
        get() = "verbatim-group"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive =
        UnpackedArchive(listOf(SourceFile("${archive.archive}$EXTENSION", archive.group)))

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no group file at ${file.toAbsolutePath()}.")
        }
        return PackedArchive(Files.readAllBytes(file), metadata.fileIds())
    }

    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(EXTENSION) || path.contains('/')) {
            return null
        }
        return path.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve("$archive$EXTENSION")
        return if (Files.exists(file)) listOf(file) else emptyList()
    }
}
