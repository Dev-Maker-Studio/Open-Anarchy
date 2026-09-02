package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Path

/**
 * One file a codec puts in a source tree.
 *
 * [path] is relative to the index directory, always `/` separated so a tree written on one
 * operating system reads the same on another.
 */
class SourceFile(val path: String, val bytes: ByteArray)

/**
 * An archive on its way out of a packed cache, as a codec is given it.
 *
 * [group] is the decompressed archive - decrypted, and with the container's framing gone - and
 * [files] is that group already split by [world.gregs.voidps.cache.store.ArchiveGroup], parallel
 * to [fileIds]. A codec that has no use for the split can ignore it; one that has no use for the
 * chunk layout can ignore that too, which is every codec but
 * [VerbatimGroupCodec], the only one that has to reproduce it.
 */
class SourceArchive(
    val index: Int,
    val archive: Int,
    /** The archive's name where the index has one, for codecs that lay files out by name. */
    val name: String?,
    val fileIds: IntArray,
    val group: ByteArray,
    val files: List<ByteArray>,
    val chunks: List<IntArray>,
    /** The metadata being recorded for this archive, already filled in by the unpacker. */
    val metadata: ArchiveMetadata
)

/**
 * What a codec produced for one archive.
 *
 * [pristine] names, per file id, the SHA-256 of the editable file whose original bytes the codec
 * kept beside it because it could not reproduce them - see `docs/cache-source.md`. An exact codec
 * leaves it empty.
 */
class UnpackedArchive(
    val files: List<SourceFile>,
    val pristine: Map<Int, String> = emptyMap()
)

/** The decompressed archive a codec rebuilt, and the file ids the reference table has to list. */
class PackedArchive(
    val group: ByteArray,
    val fileIds: IntArray
)

/**
 * The two directions between one index's archives and the files a source tree holds them as.
 *
 * A codec owns the whole layout under its index directory: what the files are called, how many
 * there are per archive, and how a path maps back to the archive it belongs to. Everything it is
 * told about an archive arrives in [SourceArchive]; everything it needs at pack time it either
 * reads back off disk or takes from [ArchiveMetadata], which the unpacker filled in.
 *
 * Implementations must be stateless and safe to call from many threads at once: unpacking and
 * packing both run an archive per task across every core.
 */
interface SourceCodec {

    /** A short name for `cache status` and build reports, like `raw-archive`. */
    val id: String

    /**
     * Whether packing what [unpack] wrote for [archive] reproduces the group byte for byte.
     *
     * True for the raw codecs, which keep the bytes. A codec that normalises - a decompiler that
     * drops dead code, an encoder that is not yet exact - says false, and the unpacker checks it
     * archive by archive and falls back to the pristine sidecar where it has to. It is per
     * archive because [ConfigCodec] dispatches to a different codec for each of its archives.
     */
    fun exact(archive: Int): Boolean = true

    /**
     * Whether [archive]'s file ids have to be carried in [ArchiveMetadata] because the layout
     * does not spell them out. True for the codecs that write a whole group to one file.
     */
    fun fileIdsFromMetadata(archive: Int): Boolean = false

    /**
     * Turn [archive] into files. [directory] is the index's directory; the returned paths are
     * relative to it and the caller does the writing, so nothing here touches the disk.
     */
    fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive

    /**
     * Rebuild [archive]'s group from what is under [directory].
     *
     * [metadata] is the archive's entry in `index.json`, which is where a codec that cannot see
     * the file ids in its own layout gets them ([ArchiveMetadata.fileIds]).
     */
    fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive

    /**
     * The archive [path] - relative to the index directory, `/` separated - belongs to, or null
     * when the path is not one of this codec's. This is how an incremental build turns a changed
     * file back into the archive it has to repack.
     */
    fun archiveOf(path: String): Int?

    /** Every file under [directory] that makes up [archive], in no particular order. */
    fun files(directory: Path, archive: Int): List<Path>
}
