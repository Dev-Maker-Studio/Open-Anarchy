package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.ReadOnlyCache
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class PackedContainer(
    val bytes: ByteArray,
    val entry: ArchiveEntry
)

internal object SourcePack {

    fun container(
        directory: Path,
        archive: Int,
        metadata: ArchiveMetadata,
        codec: SourceCodec,
        whirlpool: Boolean
    ): PackedContainer {
        val bytes: ByteArray
        val fileIds: IntArray
        if (metadata.opaque) {
            val file = directory.resolve("$archive${SourceTree.OPAQUE_EXTENSION}")
            if (!Files.isRegularFile(file)) {
                throw IOException("Opaque archive $archive has no container file at ${file.toAbsolutePath()}.")
            }
            val stored = Files.readAllBytes(file)
            bytes = stored.copyOf(stored.size + TRAILER)
            bytes[stored.size] = (metadata.version shr 8).toByte()
            bytes[stored.size + 1] = metadata.version.toByte()
            fileIds = metadata.fileIds()
        } else {
            val packed = codec.pack(directory, archive, metadata)
            val container = Container.compress(
                packed.group,
                metadata.compression,
                metadata.version and 0xffff,
                metadata.gzipOs,
                metadata.bzip2
            )
            bytes = container.encode(metadata.xtea)
            fileIds = packed.fileIds
        }
        val entry = ArchiveEntry(archive)
        entry.nameHash = metadata.nameHash
        entry.version = metadata.version
        entry.crc = CRC.calculate(bytes, 0, bytes.size - TRAILER)
        if (whirlpool) {
            entry.whirlpool = whirlpool(bytes, bytes.size - TRAILER)
        }
        for (id in fileIds) {
            entry.files.add(FileEntry(id, metadata.fileNames[id] ?: 0))
        }
        return PackedContainer(bytes, entry)
    }

    fun table(metadata: IndexMetadata, entries: List<ArchiveEntry>): ReferenceTable {
        val table = ReferenceTable(metadata.format, metadata.revision, metadata.named, metadata.whirlpool)
        for (entry in entries) {
            table.add(entry)
        }
        return table
    }

    fun tableContainer(metadata: IndexMetadata, table: ReferenceTable): ByteArray =
        Container.compress(table.encode(), metadata.compression, null, 0, metadata.bzip2).encode()

    private fun whirlpool(bytes: ByteArray, length: Int): ByteArray {
        val output = ByteArray(ReadOnlyCache.WHIRLPOOL_SIZE)
        val hash = Whirlpool()
        hash.reset()
        hash.add(bytes, 0, length)
        hash.finalize(output)
        return output
    }

    private const val TRAILER = 2
}
