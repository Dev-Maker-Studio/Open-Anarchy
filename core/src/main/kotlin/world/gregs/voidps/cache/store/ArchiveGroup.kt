package world.gregs.voidps.cache.store

class GroupFiles(
    val files: List<ByteArray>,
    val chunks: List<IntArray>
)

object ArchiveGroup {

    fun split(group: ByteArray, fileIds: IntArray): GroupFiles {
        val fileCount = fileIds.size
        require(fileCount > 0) { "A group has at least one file." }
        if (fileCount == 1) {
            return GroupFiles(listOf(group.copyOf()), listOf(intArrayOf(group.size)))
        }
        require(group.isNotEmpty()) { "A $fileCount file group cannot be empty." }
        var tableOffset = group.size - 1
        val chunkCount = group[tableOffset].toInt() and 0xff
        require(chunkCount > 0) { "A $fileCount file group claims 0 chunks." }
        tableOffset -= chunkCount * fileCount * 4
        require(tableOffset >= 0) {
            "A $fileCount file group of ${group.size} bytes cannot hold a $chunkCount chunk table."
        }

        val chunks = ArrayList<IntArray>(chunkCount)
        val sizes = IntArray(fileCount)
        var offset = tableOffset
        for (chunk in 0 until chunkCount) {
            val lengths = IntArray(fileCount)
            var length = 0
            for (file in 0 until fileCount) {
                length += getInt(group, offset)
                offset += 4
                lengths[file] = length
                sizes[file] += length
            }
            chunks.add(lengths)
        }

        val files = List(fileCount) { ByteArray(sizes[it]) }
        val written = IntArray(fileCount)
        var position = 0
        for (chunk in chunks) {
            for (file in 0 until fileCount) {
                val length = chunk[file]
                require(position + length <= tableOffset) {
                    "A $fileCount file group's chunk table describes more data than the group holds."
                }
                System.arraycopy(group, position, files[file], written[file], length)
                position += length
                written[file] += length
            }
        }
        return GroupFiles(files, chunks)
    }

    fun join(files: List<ByteArray>, chunks: List<IntArray>? = null): ByteArray {
        require(files.isNotEmpty()) { "A group has at least one file." }
        if (files.size == 1) {
            return files[0].copyOf()
        }
        val fileCount = files.size
        val layout = chunks ?: listOf(IntArray(fileCount) { files[it].size })
        require(layout.isNotEmpty() && layout.size <= 255) {
            "A group has 1 to 255 chunks, not ${layout.size}."
        }
        var total = 0
        for (chunk in layout) {
            require(chunk.size == fileCount) {
                "Chunk layout describes ${chunk.size} files but the group has $fileCount."
            }
            for (length in chunk) {
                require(length >= 0) { "A chunk length cannot be negative." }
                total += length
            }
        }
        for (file in 0 until fileCount) {
            val expected = layout.sumOf { it[file] }
            require(expected == files[file].size) {
                "Chunk layout gives file $file $expected bytes but it has ${files[file].size}."
            }
        }

        val output = ByteArray(total + layout.size * fileCount * 4 + 1)
        val read = IntArray(fileCount)
        var position = 0
        for (chunk in layout) {
            for (file in 0 until fileCount) {
                val length = chunk[file]
                System.arraycopy(files[file], read[file], output, position, length)
                position += length
                read[file] += length
            }
        }
        for (chunk in layout) {
            var previous = 0
            for (file in 0 until fileCount) {
                putInt(output, position, chunk[file] - previous)
                previous = chunk[file]
                position += 4
            }
        }
        output[position] = layout.size.toByte()
        return output
    }

    private fun getInt(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
