package world.gregs.voidps.cache.store

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class FileStore private constructor(
    val directory: File,
    private val data: RandomAccessFile,
    private val indexFiles: Array<RandomAccessFile?>,
    val writable: Boolean,
    private var indexCount: Int
) : Closeable {

    private val dataChannel: FileChannel = data.channel
    private val indexChannels: Array<FileChannel?> = Array(indexFiles.size) { indexFiles[it]?.channel }
    private val lock = Any()

    @Volatile
    var durable: Boolean = true

    fun indexCount(): Int {
        val fromTable = ((indexChannels[REFERENCE_INDEX]?.size() ?: 0L) / INDEX_ENTRY_SIZE).toInt()
        return maxOf(indexCount, fromTable)
    }

    fun hasIndex(index: Int): Boolean = indexChannels.getOrNull(index) != null

    fun read(index: Int, archive: Int): ByteArray? {
        val indexChannel = indexChannels.getOrNull(index) ?: return null
        if (archive < 0) {
            return null
        }
        val entry = ByteBuffer.allocate(INDEX_ENTRY_SIZE)
        val entryPosition = archive.toLong() * INDEX_ENTRY_SIZE
        if (indexChannel.read(entry, entryPosition) < INDEX_ENTRY_SIZE) {
            return null
        }
        val size = medium(entry.array(), 0)
        var sector = medium(entry.array(), 3)
        val dataLength = dataChannel.size()
        if (size <= 0 || sector <= 0 || sector > dataLength / SECTOR_SIZE) {
            return null
        }

        val big = archive > MAX_SMALL_ARCHIVE
        val headerSize = if (big) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
        val payloadSize = if (big) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
        val output = ByteArray(size)
        val buffer = ByteBuffer.allocate(SECTOR_SIZE)
        var read = 0
        var chunk = 0
        while (read < size) {
            if (sector <= 0) {
                return null
            }
            val required = minOf(size - read, payloadSize)
            buffer.clear()
            buffer.limit(required + headerSize)
            if (dataChannel.read(buffer, sector.toLong() * SECTOR_SIZE) < required + headerSize) {
                return null
            }
            val raw = buffer.array()
            val id = if (big) int(raw, 0) else short(raw, 0)
            val sectorChunk = short(raw, if (big) 4 else 2)
            val next = medium(raw, if (big) 6 else 4)
            val sectorIndex = raw[if (big) 9 else 7].toInt() and 0xff
            if (id != archive || sectorChunk != chunk || sectorIndex != index) {
                return null
            }
            if (next < 0 || next > dataLength / SECTOR_SIZE) {
                return null
            }
            System.arraycopy(raw, headerSize, output, read, required)
            read += required
            sector = next
            chunk++
        }
        return output
    }

    fun write(index: Int, archive: Int, container: ByteArray) {
        check(writable) { "This store was opened read only." }
        require(index in 0 until indexFiles.size) { "Index $index is out of range." }
        require(archive >= 0) { "Archive id $archive is negative." }
        require(container.isNotEmpty()) { "Refusing to write an empty container." }
        require(container.size <= MAX_CONTAINER_SIZE) {
            "Container of ${container.size} bytes does not fit the index entry's three byte size."
        }
        synchronized(lock) {
            val indexFile = indexFiles[index]
                ?: throw FileNotFoundException("No ${CACHE_FILE_NAME}.idx$index in ${directory.absolutePath}.")

            val big = archive > MAX_SMALL_ARCHIVE
            val headerSize = if (big) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
            val payloadSize = if (big) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
            val needed = (container.size + payloadSize - 1) / payloadSize

            val sectors = reusableChain(index, archive, needed, payloadSize, big) ?: appendChain(needed)

            val contiguous = (1 until needed).all { sectors[it] == sectors[it - 1] + 1 }
            val buffer = ByteArray(if (contiguous) needed * SECTOR_SIZE else SECTOR_SIZE)
            var written = 0
            for (chunk in 0 until needed) {
                val sector = sectors[chunk]
                val length = minOf(container.size - written, payloadSize)
                val next = if (chunk == needed - 1) 0 else sectors[chunk + 1]
                var offset = if (contiguous) chunk * SECTOR_SIZE else 0
                val start = offset
                if (!contiguous) {
                    buffer.fill(0)
                }
                if (big) {
                    putInt(buffer, offset, archive)
                    offset += 4
                } else {
                    putShort(buffer, offset, archive)
                    offset += 2
                }
                putShort(buffer, offset, chunk)
                offset += 2
                putMedium(buffer, offset, next)
                offset += 3
                buffer[offset] = index.toByte()
                System.arraycopy(container, written, buffer, start + headerSize, length)
                if (!contiguous) {
                    data.seek(sector.toLong() * SECTOR_SIZE)
                    data.write(buffer, 0, headerSize + length)
                }
                written += length
            }
            if (contiguous) {
                data.seek(sectors[0].toLong() * SECTOR_SIZE)
                data.write(buffer, 0, (needed - 1) * SECTOR_SIZE + headerSize + minOf(container.size - (needed - 1) * payloadSize, payloadSize))
            }
            if (durable) {
                data.fd.sync()
            }

            val entry = ByteArray(INDEX_ENTRY_SIZE)
            putMedium(entry, 0, container.size)
            putMedium(entry, 3, sectors[0])
            indexFile.seek(archive.toLong() * INDEX_ENTRY_SIZE)
            indexFile.write(entry)
            if (durable) {
                indexFile.fd.sync()
            }
            if (index == REFERENCE_INDEX && archive >= indexCount) {
                indexCount = archive + 1
            }
        }
    }

    fun remove(index: Int, archive: Int) {
        check(writable) { "This store was opened read only." }
        require(archive >= 0) { "Archive id $archive is negative." }
        synchronized(lock) {
            val indexFile = indexFiles.getOrNull(index) ?: return
            if (indexFile.length() < archive.toLong() * INDEX_ENTRY_SIZE + INDEX_ENTRY_SIZE) {
                return
            }
            indexFile.seek(archive.toLong() * INDEX_ENTRY_SIZE)
            indexFile.write(ByteArray(INDEX_ENTRY_SIZE))
            if (durable) {
                indexFile.fd.sync()
            }
        }
    }

    fun archives(index: Int): IntArray {
        val channel = indexChannels.getOrNull(index) ?: return IntArray(0)
        val length = channel.size()
        if (length < INDEX_ENTRY_SIZE) {
            return IntArray(0)
        }
        val count = (length / INDEX_ENTRY_SIZE).toInt()
        val buffer = ByteBuffer.allocate(count * INDEX_ENTRY_SIZE)
        var position = 0L
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            position += read
        }
        val raw = buffer.array()
        var found = 0
        val ids = IntArray(count)
        for (archive in 0 until count) {
            val offset = archive * INDEX_ENTRY_SIZE
            if (medium(raw, offset) > 0 && medium(raw, offset + 3) > 0) {
                ids[found++] = archive
            }
        }
        return ids.copyOf(found)
    }

    private fun reusableChain(index: Int, archive: Int, needed: Int, payloadSize: Int, big: Boolean): IntArray? {
        val channel = indexChannels[index] ?: return null
        val entry = ByteBuffer.allocate(INDEX_ENTRY_SIZE)
        if (channel.read(entry, archive.toLong() * INDEX_ENTRY_SIZE) < INDEX_ENTRY_SIZE) {
            return null
        }
        val size = medium(entry.array(), 0)
        var sector = medium(entry.array(), 3)
        if (size <= 0 || sector <= 0) {
            return null
        }
        val existing = (size + payloadSize - 1) / payloadSize
        if (needed > existing) {
            return null
        }
        val dataLength = dataChannel.size()
        val headerSize = if (big) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
        val header = ByteBuffer.allocate(headerSize)
        val sectors = IntArray(needed)
        for (chunk in 0 until needed) {
            if (sector <= 0 || sector > dataLength / SECTOR_SIZE) {
                return null
            }
            header.clear()
            if (dataChannel.read(header, sector.toLong() * SECTOR_SIZE) < headerSize) {
                return null
            }
            val raw = header.array()
            val id = if (big) int(raw, 0) else short(raw, 0)
            val sectorChunk = short(raw, if (big) 4 else 2)
            val next = medium(raw, if (big) 6 else 4)
            val sectorIndex = raw[if (big) 9 else 7].toInt() and 0xff
            if (id != archive || sectorChunk != chunk || sectorIndex != index) {
                return null
            }
            sectors[chunk] = sector
            sector = next
        }
        return sectors
    }

    private fun appendChain(needed: Int): IntArray {
        val start = (data.length() + SECTOR_SIZE - 1) / SECTOR_SIZE
        check(start + needed <= MAX_SECTOR) {
            "The data file has outgrown the three byte sector pointer; the cache needs compacting."
        }
        val first = start.toInt()
        return IntArray(needed) { first + it }
    }

    override fun close() {
        runCatching { data.close() }
        for (file in indexFiles) {
            if (file != null) {
                runCatching { file.close() }
            }
        }
    }

    companion object {
        const val CACHE_FILE_NAME = "main_file_cache"

        const val REFERENCE_INDEX = 255

        const val SECTOR_SIZE = 520
        const val SECTOR_HEADER_SIZE_SMALL = 8
        const val SECTOR_DATA_SIZE_SMALL = 512
        const val SECTOR_HEADER_SIZE_BIG = 10
        const val SECTOR_DATA_SIZE_BIG = 510
        const val INDEX_ENTRY_SIZE = 6

        const val MAX_SMALL_ARCHIVE = 0xffff

        const val MAX_CONTAINER_SIZE = 0xffffff

        const val MAX_SECTOR = 0xffffffL

        private const val INDEX_SLOTS = 256

        fun open(dir: File, writable: Boolean = false): FileStore {
            val dataFile = File(dir, "$CACHE_FILE_NAME.dat2")
            if (!dataFile.exists()) {
                throw FileNotFoundException("No cache data file at ${dataFile.absolutePath}.")
            }
            val mode = if (writable) "rw" else "r"
            val data = RandomAccessFile(dataFile, mode)
            val indexFiles = arrayOfNulls<RandomAccessFile>(INDEX_SLOTS)
            for (index in 0 until INDEX_SLOTS) {
                val file = File(dir, "$CACHE_FILE_NAME.idx$index")
                if (file.exists()) {
                    indexFiles[index] = RandomAccessFile(file, mode)
                }
            }
            val referenceLength = indexFiles[REFERENCE_INDEX]?.length() ?: 0L
            return FileStore(dir, data, indexFiles, writable, (referenceLength / INDEX_ENTRY_SIZE).toInt())
        }

        fun create(dir: File, indexCount: Int): FileStore {
            require(indexCount in 0 until REFERENCE_INDEX) { "Index count $indexCount is out of range." }
            dir.mkdirs()
            val dataFile = File(dir, "$CACHE_FILE_NAME.dat2")
            RandomAccessFile(dataFile, "rw").use { file ->
                file.setLength(SECTOR_SIZE.toLong())
            }
            for (index in 0 until indexCount) {
                File(dir, "$CACHE_FILE_NAME.idx$index").createNewFile()
            }
            File(dir, "$CACHE_FILE_NAME.idx$REFERENCE_INDEX").createNewFile()
            val store = open(dir, writable = true)
            store.indexCount = indexCount
            return store
        }

        private fun short(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

        private fun medium(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 16) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                (data[offset + 2].toInt() and 0xff)

        private fun int(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)

        private fun putShort(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value shr 8).toByte()
            data[offset + 1] = value.toByte()
        }

        private fun putMedium(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value shr 16).toByte()
            data[offset + 1] = (value shr 8).toByte()
            data[offset + 2] = value.toByte()
        }

        private fun putInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value shr 24).toByte()
            data[offset + 1] = (value shr 16).toByte()
            data[offset + 2] = (value shr 8).toByte()
            data[offset + 3] = value.toByte()
        }
    }
}
