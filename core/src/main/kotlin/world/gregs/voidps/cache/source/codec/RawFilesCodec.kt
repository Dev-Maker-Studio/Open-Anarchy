package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class RawFilesCodec(private val flat: Boolean = false) : SourceCodec {

    override val id: String
        get() = if (flat) "raw-files-flat" else "raw-files"

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size)
        for ((position, id) in archive.fileIds.withIndex()) {
            files.add(SourceFile(path(archive.archive, id), archive.files[position]))
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = fileIds(directory, archive)
        check(ids.isNotEmpty()) { "Archive $archive has no files in ${directory.toAbsolutePath()}." }
        val folder = folder(directory, archive)
        val files = ids.map { Files.readAllBytes(folder.resolve("$it$EXTENSION")) }
        return PackedArchive(ArchiveGroup.join(files), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (flat) {
            return null
        }
        if (!path.endsWith(EXTENSION)) {
            return null
        }
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        if (path.substring(separator + 1).dropLast(EXTENSION.length).toIntOrNull() == null) {
            return null
        }
        return path.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = folder(directory, archive)
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { it.name.endsWith(EXTENSION) && it.name.dropLast(EXTENSION.length).toIntOrNull() != null }
                .toList()
        }
    }

    fun fileIds(directory: Path, archive: Int): IntArray {
        val ids = files(directory, archive).mapNotNull { it.name.dropLast(EXTENSION.length).toIntOrNull() }
        return ids.sorted().toIntArray()
    }

    private fun path(archive: Int, file: Int): String = if (flat) "$file$EXTENSION" else "$archive/$file$EXTENSION"

    private fun folder(directory: Path, archive: Int): Path =
        if (flat) directory else directory.resolve(archive.toString())

    companion object {
        const val EXTENSION = ".dat"

        val NESTED = RawFilesCodec(flat = false)

        val FLAT = RawFilesCodec(flat = true)
    }
}
