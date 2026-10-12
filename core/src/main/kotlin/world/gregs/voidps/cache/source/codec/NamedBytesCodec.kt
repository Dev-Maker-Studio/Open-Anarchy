package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceTree
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class NamedBytesCodec(
    override val id: String,
    private val names: (Int) -> String?
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        return UnpackedArchive(listOf(SourceFile(path(archive.metadata.nameHash, archive.archive), archive.files[0])))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve(path(metadata.nameHash, archive))
        if (!Files.isRegularFile(file)) {
            throw IOException("Archive $archive has no file at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val fallback = fallback(path)
        if (fallback != null) {
            return fallback
        }
        return ArchiveNames.archive(directory, path.hashCode())
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = ArchiveNames.nameHash(directory, archive) ?: return emptyList()
        val file = directory.resolve(path(hash, archive))
        return if (Files.exists(file)) listOf(file) else emptyList()
    }

    private fun path(hash: Int, archive: Int): String =
        names(hash) ?: "$archive${RawArchiveCodec.EXTENSION}"

    private fun fallback(path: String): Int? {
        if (path.contains('/') || !path.endsWith(RawArchiveCodec.EXTENSION)) {
            return null
        }
        return path.dropLast(RawArchiveCodec.EXTENSION.length).toIntOrNull()
    }
}

internal object ArchiveNames {

    fun archive(directory: Path, hash: Int): Int? {
        val metadata = read(directory) ?: return null
        for ((archive, entry) in metadata.archives) {
            if (entry.nameHash == hash) {
                return archive
            }
        }
        return null
    }

    fun nameHash(directory: Path, archive: Int): Int? = read(directory)?.archive(archive)?.nameHash

    private fun read(directory: Path): IndexMetadata? {
        val file = directory.resolve(SourceTree.INDEX_FILE)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return IndexMetadata.read(Files.readString(file), 0)
    }
}
