package org.darkan.tools.cache

import world.gregs.voidps.cache.secure.CRC
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal read/write access to the raw sector store of an on-disk RS cache.
 *
 * The shared [world.gregs.voidps.cache.Cache] implementations open every file read-only and
 * throw from `write`, which is the right default for a game server. The map editor needs the
 * opposite, so this opens `main_file_cache.dat2` and the index it is told to touch with
 * `"rw"` and does the sector bookkeeping itself. Nothing here is on any render path.
 *
 * Write strategy is deliberately the most conservative one available: **new sectors are
 * always appended past the end of `dat2`** and the index entry is then repointed at them.
 * Existing sector chains are never overwritten, so a half-finished write can only ever leave
 * unreferenced bytes at the tail of the data file, never a damaged neighbouring archive. The
 * cost is that `dat2` grows by the archive size on every save; at ~130 KB for a map archive
 * against an 876 MB data file that is not worth reclaiming.
 *
 * Ordering matters for crash safety: sectors are written and forced to disk *before* the
 * six-byte index entry that points at them, so the entry only ever becomes visible once the
 * data behind it is durable.
 */
class CacheArchiveWriter(private val cacheDir: File) : Closeable {

    private val dataFile = File(cacheDir, "$CACHE_FILE_NAME.dat2")
    private val data: RandomAccessFile = RandomAccessFile(requireExists(dataFile), "rw")
    private val indexFiles = HashMap<Int, RandomAccessFile>()

    /** Container bytes of an archive - the compression header, payload and trailing version. */
    fun readContainer(index: Int, archive: Int): ByteArray? {
        val indexFile = indexFile(index) ?: return null
        val entryPosition = archive.toLong() * INDEX_ENTRY_SIZE
        if (indexFile.length() < entryPosition + INDEX_ENTRY_SIZE) return null
        indexFile.seek(entryPosition)
        val entry = ByteArray(INDEX_ENTRY_SIZE)
        indexFile.readFully(entry)
        val size = readMedium(entry, 0)
        var sector = readMedium(entry, 3)
        if (size <= 0 || sector <= 0) return null

        val big = archive > 0xffff
        val headerSize = if (big) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
        val payloadSize = if (big) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
        val output = ByteArray(size)
        val sectorBuffer = ByteArray(SECTOR_SIZE)
        var read = 0
        var chunk = 0
        while (read < size) {
            if (sector <= 0) return null
            val required = minOf(size - read, payloadSize)
            data.seek(sector.toLong() * SECTOR_SIZE)
            data.readFully(sectorBuffer, 0, required + headerSize)
            val id = if (big) readInt(sectorBuffer, 0) else readShort(sectorBuffer, 0)
            val sectorChunk = readShort(sectorBuffer, if (big) 4 else 2)
            val next = readMedium(sectorBuffer, if (big) 6 else 4)
            val sectorIndex = sectorBuffer[if (big) 9 else 7].toInt() and 0xff
            check(id == archive && sectorChunk == chunk && sectorIndex == index) {
                "Sector chain for index $index archive $archive is inconsistent at chunk $chunk " +
                    "(got id=$id chunk=$sectorChunk index=$sectorIndex)"
            }
            System.arraycopy(sectorBuffer, headerSize, output, read, required)
            read += required
            sector = next
            chunk++
        }
        return output
    }

    /**
     * Replace an archive's container bytes.
     *
     * @return the sector the new chain starts at, for logging
     */
    fun writeContainer(index: Int, archive: Int, container: ByteArray): Int {
        require(container.isNotEmpty()) { "Refusing to write an empty container" }
        require(container.size <= MAX_CONTAINER_SIZE) {
            "Container of ${container.size} bytes exceeds the 3-byte index size field"
        }
        val indexFile = indexFile(index)
            ?: throw IllegalStateException("No $CACHE_FILE_NAME.idx$index in ${cacheDir.absolutePath}")

        val big = archive > 0xffff
        val headerSize = if (big) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
        val payloadSize = if (big) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL

        // Append: round the existing length up to a sector boundary and start there.
        val startSectorLong = (data.length() + SECTOR_SIZE - 1) / SECTOR_SIZE
        val sectorCount = (container.size + payloadSize - 1) / payloadSize
        check(startSectorLong + sectorCount <= MAX_SECTOR) {
            "The data file has grown past the ${MAX_SECTOR * SECTOR_SIZE / (1L shl 30)} GiB addressable " +
                "by a three-byte sector pointer; the cache needs compacting before it can be written to"
        }
        val startSector = startSectorLong.toInt()

        val sectorBuffer = ByteArray(SECTOR_SIZE)
        var written = 0
        for (chunk in 0 until sectorCount) {
            val sector = startSector + chunk
            val length = minOf(container.size - written, payloadSize)
            val next = if (chunk == sectorCount - 1) 0 else sector + 1
            sectorBuffer.fill(0)
            var offset = 0
            if (big) {
                writeInt(sectorBuffer, offset, archive)
                offset += 4
            } else {
                writeShort(sectorBuffer, offset, archive)
                offset += 2
            }
            writeShort(sectorBuffer, offset, chunk)
            offset += 2
            writeMedium(sectorBuffer, offset, next)
            offset += 3
            sectorBuffer[offset] = index.toByte()
            System.arraycopy(container, written, sectorBuffer, headerSize, length)
            data.seek(sector.toLong() * SECTOR_SIZE)
            data.write(sectorBuffer, 0, headerSize + length)
            written += length
        }
        // The index entry must not become visible before the sectors it points at.
        data.fd.sync()

        val entry = ByteArray(INDEX_ENTRY_SIZE)
        writeMedium(entry, 0, container.size)
        writeMedium(entry, 3, startSector)
        indexFile.seek(archive.toLong() * INDEX_ENTRY_SIZE)
        indexFile.write(entry)
        indexFile.fd.sync()
        return startSector
    }

    private fun indexFile(index: Int): RandomAccessFile? = indexFiles.getOrPut(index) {
        val file = File(cacheDir, "$CACHE_FILE_NAME.idx$index")
        if (!file.exists()) return null
        RandomAccessFile(file, "rw")
    }

    override fun close() {
        indexFiles.values.forEach { runCatching { it.close() } }
        indexFiles.clear()
        runCatching { data.close() }
    }

    companion object {
        const val CACHE_FILE_NAME = "main_file_cache"

        private const val SECTOR_SIZE = 520
        private const val SECTOR_HEADER_SIZE_SMALL = 8
        private const val SECTOR_DATA_SIZE_SMALL = 512
        private const val SECTOR_HEADER_SIZE_BIG = 10
        private const val SECTOR_DATA_SIZE_BIG = 510
        private const val INDEX_ENTRY_SIZE = 6

        /** The index entry stores the container size in three bytes. */
        private const val MAX_CONTAINER_SIZE = 0xffffff

        /** Sector pointers are three bytes wide, both in the index entry and in sector headers. */
        private const val MAX_SECTOR = 0xffffffL

        private const val COMPRESSION_NONE = 0

        private fun requireExists(file: File): File {
            check(file.exists()) { "No cache data file at ${file.absolutePath}" }
            return file
        }

        private fun readShort(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

        private fun readMedium(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 16) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                (data[offset + 2].toInt() and 0xff)

        private fun readInt(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)

        private fun writeShort(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value shr 8) and 0xff).toByte()
            data[offset + 1] = (value and 0xff).toByte()
        }

        private fun writeMedium(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value shr 16) and 0xff).toByte()
            data[offset + 1] = ((value shr 8) and 0xff).toByte()
            data[offset + 2] = (value and 0xff).toByte()
        }

        private fun writeInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value shr 24) and 0xff).toByte()
            data[offset + 1] = ((value shr 16) and 0xff).toByte()
            data[offset + 2] = ((value shr 8) and 0xff).toByte()
            data[offset + 3] = (value and 0xff).toByte()
        }

        /**
         * Splits a container into its compression header, payload and trailing version bytes.
         *
         * A container is `[type][int compressedSize]` then, for every type but `0`, an
         * `[int decompressedSize]`, then the payload, then an optional two-byte version that
         * the reference table pairs with its own version field.
         */
        fun describeContainer(container: ByteArray): ContainerInfo {
            require(container.size >= 5) { "Container of ${container.size} bytes is too short" }
            val type = container[0].toInt() and 0xff
            val compressedSize = readInt(container, 1) and 0xffffff
            val headerSize = if (type == COMPRESSION_NONE) 5 else 9
            val payloadEnd = headerSize + compressedSize
            require(payloadEnd <= container.size) {
                "Container claims $compressedSize payload bytes but only has ${container.size - headerSize}"
            }
            return ContainerInfo(
                type = type,
                headerSize = headerSize,
                payloadOffset = headerSize,
                payloadLength = compressedSize,
                trailing = container.copyOfRange(payloadEnd, container.size),
            )
        }

        /**
         * Wraps [payload] as an uncompressed container, keeping [trailing] (the version bytes)
         * byte for byte.
         *
         * Re-emitting uncompressed rather than re-running the original codec keeps the write
         * path free of any compressor whose output would have to be trusted, and every reader
         * in this codebase and in the client handles type `0`.
         */
        fun buildContainer(payload: ByteArray, trailing: ByteArray): ByteArray {
            val container = ByteArray(5 + payload.size + trailing.size)
            container[0] = COMPRESSION_NONE.toByte()
            writeInt(container, 1, payload.size)
            System.arraycopy(payload, 0, container, 5, payload.size)
            System.arraycopy(trailing, 0, container, 5 + payload.size, trailing.size)
            return container
        }

        /** JS5 archive CRC: the whole container minus the trailing version bytes. */
        fun containerCrc(container: ByteArray, trailingSize: Int): Int =
            CRC.calculate(container, 0, container.size - trailingSize)
    }
}

/** The three parts of a cache container. */
class ContainerInfo(
    val type: Int,
    val headerSize: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    /** Bytes after the payload - normally the two-byte archive version, sometimes nothing. */
    val trailing: ByteArray,
)
