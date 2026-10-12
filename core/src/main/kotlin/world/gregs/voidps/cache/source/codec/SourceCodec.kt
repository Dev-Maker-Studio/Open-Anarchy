package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.nio.file.Path

class SourceFile(val path: String, val bytes: ByteArray)

class SourceArchive(
    val index: Int,
    val archive: Int,
    val name: String?,
    val fileIds: IntArray,
    val group: ByteArray,
    val files: List<ByteArray>,
    val chunks: List<IntArray>,
    val metadata: ArchiveMetadata
)

class UnpackedArchive(
    val files: List<SourceFile>,
    val pristine: Map<Int, String> = emptyMap()
)

class PackedArchive(
    val group: ByteArray,
    val fileIds: IntArray
)

sealed interface Owner {

    data class Archive(val id: Int) : Owner

    data object Index : Owner

    data object None : Owner
}

interface SourceCodec {

    val id: String

    val functional: Boolean
        get() = false

    fun exact(archive: Int): Boolean = true

    fun fileIdsFromMetadata(archive: Int): Boolean = false

    fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive

    fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> = emptyList()

    fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive

    fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {}

    fun archiveOf(path: String): Int?

    fun archiveOf(directory: Path, path: String): Int? = archiveOf(path)

    fun owner(directory: Path, path: String): Owner {
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    fun files(directory: Path, archive: Int): List<Path>

    fun pristineFiles(directory: Path, archive: Int): List<Path> =
        files(directory, archive).filter { SourceFiles.isPristine(it.fileName.toString()) }
}
