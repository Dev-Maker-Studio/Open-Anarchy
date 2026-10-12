package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.nio.file.Files
import java.nio.file.Path

class ArchiveDirectoryCodec(
    override val id: String,
    val name: (archive: Int) -> String,
    private val archive: (name: String) -> Int,
    val codec: (archive: Int) -> SourceCodec,
    private val nested: Boolean = true
) : SourceCodec {

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val unpacked = codec(archive.archive).unpack(folder(directory, archive.archive), archive)
        if (!nested) {
            return unpacked
        }
        val name = name(archive.archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(folder(directory, archive), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        val separator = editable.indexOf('/')
        val key = if (nested) {
            if (separator <= 0) {
                return null
            }
            editable.substring(0, separator)
        } else {
            if (separator >= 0) {
                return null
            }
            val extension = editable.lastIndexOf('.')
            if (extension <= 0) {
                return null
            }
            editable.substring(0, extension)
        }
        val id = archive(key)
        return if (id < 0) null else id
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = folder(directory, archive)
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }

    private fun folder(directory: Path, archive: Int): Path =
        if (nested) directory.resolve(name(archive)) else directory
}
