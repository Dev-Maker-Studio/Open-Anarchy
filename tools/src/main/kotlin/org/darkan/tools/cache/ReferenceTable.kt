package org.darkan.tools.cache

import world.gregs.voidps.buffer.read.BufferReader
import java.util.zip.Inflater

/**
 * Just enough of an index's reference table (index 255, archive = the index id) to keep it
 * honest after an archive has been rewritten.
 *
 * The table carries a CRC and a version for every archive in its index. Rewriting an archive
 * without updating them leaves the cache internally inconsistent: JS5 clients compare the
 * archive they download against the table's CRC and reject the mismatch, and a client that
 * already holds the old copy never notices the change because the version did not move.
 * Both fields sit at fixed offsets once the variable-length header has been walked, so this
 * patches them in place rather than re-encoding the whole table.
 *
 * Only the offsets are parsed; nothing here interprets the file lists.
 */
object ReferenceTable {

    private const val FLAG_NAMES = 0x1
    private const val FLAG_WHIRLPOOL = 0x2
    private const val FLAG_SIZES = 0x4
    private const val FLAG_UNKNOWN_8 = 0x8

    private const val WHIRLPOOL_SIZE = 64

    private const val COMPRESSION_NONE = 0
    private const val COMPRESSION_GZIP = 2

    /** Byte offsets of the mutable fields for one archive within a decompressed table. */
    class Entry(
        val archive: Int,
        val ordinal: Int,
        val crcOffset: Int,
        val versionOffset: Int,
    )

    class Table(
        val bytes: ByteArray,
        /** Offset of the index revision int, or -1 for format version 5 which has none. */
        val revisionOffset: Int,
        private val entries: Map<Int, Entry>,
    ) {
        fun entry(archive: Int): Entry? = entries[archive]

        fun crc(archive: Int): Int? = entry(archive)?.let { readInt(bytes, it.crcOffset) }

        fun version(archive: Int): Int? = entry(archive)?.let { readInt(bytes, it.versionOffset) }

        fun revision(): Int? = if (revisionOffset < 0) null else readInt(bytes, revisionOffset)

        fun setCrc(archive: Int, value: Int) {
            val entry = entry(archive) ?: throw IllegalArgumentException("Archive $archive is not in this table")
            writeInt(bytes, entry.crcOffset, value)
        }

        fun setVersion(archive: Int, value: Int) {
            val entry = entry(archive) ?: throw IllegalArgumentException("Archive $archive is not in this table")
            writeInt(bytes, entry.versionOffset, value)
        }

        fun setRevision(value: Int) {
            if (revisionOffset >= 0) writeInt(bytes, revisionOffset, value)
        }
    }

    /**
     * Parse the header of a decompressed reference table far enough to locate the CRC and
     * version of every archive.
     */
    fun parse(table: ByteArray): Table {
        val reader = BufferReader(table)
        val version = reader.readUnsignedByte()
        require(version in 5..7) { "Unknown reference table version $version" }
        val revisionOffset = if (version >= 6) {
            val offset = reader.position()
            reader.skip(4)
            offset
        } else {
            -1
        }
        val flags = reader.readUnsignedByte()
        val archiveCount = if (version >= 7) reader.readBigSmart() else reader.readUnsignedShort()

        var previous = 0
        val archiveIds = IntArray(archiveCount) {
            val delta = if (version >= 7) reader.readBigSmart() else reader.readUnsignedShort()
            previous += delta
            previous
        }
        if (flags and FLAG_NAMES != 0) reader.skip(archiveCount * 4)

        val crcSection = reader.position()
        reader.skip(archiveCount * 4)
        if (flags and FLAG_UNKNOWN_8 != 0) reader.skip(archiveCount * 4)
        if (flags and FLAG_WHIRLPOOL != 0) reader.skip(archiveCount * WHIRLPOOL_SIZE)
        if (flags and FLAG_SIZES != 0) reader.skip(archiveCount * 8)
        val versionSection = reader.position()

        val entries = HashMap<Int, Entry>(archiveCount)
        for (ordinal in 0 until archiveCount) {
            val archive = archiveIds[ordinal]
            entries[archive] = Entry(
                archive = archive,
                ordinal = ordinal,
                crcOffset = crcSection + ordinal * 4,
                versionOffset = versionSection + ordinal * 4,
            )
        }
        return Table(table, revisionOffset, entries)
    }

    /**
     * Decompress a container payload.
     *
     * Only the two forms that appear on reference tables are supported. BZIP2 and LZMA would
     * need their compressors as well as their decompressors to survive a patch cycle, and no
     * reference table in this cache uses them - failing loudly beats guessing.
     */
    fun decompress(container: ByteArray): ByteArray {
        val info = CacheArchiveWriter.describeContainer(container)
        return when (info.type) {
            COMPRESSION_NONE -> container.copyOfRange(info.payloadOffset, info.payloadOffset + info.payloadLength)
            COMPRESSION_GZIP -> {
                val decompressedSize = readInt(container, 5) and 0xffffff
                val streamStart = info.payloadOffset
                require(
                    (container[streamStart].toInt() and 0xff) == 0x1f &&
                        (container[streamStart + 1].toInt() and 0xff) == 0x8b
                ) { "Container claims gzip but has no gzip magic" }
                // 10-byte gzip header, 8-byte trailer; the deflate stream is what is left.
                val deflateStart = streamStart + 10
                val deflateLength = info.payloadLength - 18
                val inflater = Inflater(true)
                try {
                    inflater.setInput(container, deflateStart, deflateLength)
                    val output = ByteArray(decompressedSize)
                    var written = 0
                    while (written < decompressedSize && !inflater.finished()) {
                        val read = inflater.inflate(output, written, decompressedSize - written)
                        if (read == 0) break
                        written += read
                    }
                    check(written == decompressedSize) {
                        "Inflated $written of $decompressedSize reference table bytes"
                    }
                    output
                } finally {
                    inflater.end()
                }
            }
            else -> throw UnsupportedOperationException(
                "Reference table uses compression type ${info.type}; only none and gzip are supported"
            )
        }
    }

    private fun readInt(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xff).toByte()
        data[offset + 1] = ((value shr 16) and 0xff).toByte()
        data[offset + 2] = ((value shr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }
}
