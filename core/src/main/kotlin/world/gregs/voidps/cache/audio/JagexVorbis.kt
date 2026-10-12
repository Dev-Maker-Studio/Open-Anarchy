package world.gregs.voidps.cache.audio

object JagexVorbis {

    class Sample(
        val sampleRate: Int,
        val samples: Int,
        val loopStart: Int,
        val loopEnd: Int,
        val loopConsistent: Boolean,
        val packets: Int
    ) {
        val millis: Int
            get() = if (sampleRate <= 0) 0 else (samples.toLong() * 1000 / sampleRate).toInt()
    }

    class Setup(val blockSize0: Int, val blockSize1: Int, val codebooks: Int)

    fun sample(data: ByteArray): Sample {
        require(data.size >= HEADER) { "An index 14 sample is at least $HEADER bytes long." }
        val end = int(data, 12)
        return Sample(
            sampleRate = int(data, 0),
            samples = int(data, 4),
            loopStart = int(data, 8),
            loopEnd = if (end < 0) end.inv() else end,
            loopConsistent = end < 0,
            packets = int(data, 16)
        )
    }

    fun setup(data: ByteArray): Setup {
        require(data.size >= 2) { "An index 14 setup header is at least two bytes long." }
        val sizes = data[0].toInt() and 0xff
        return Setup(
            blockSize0 = 1 shl (sizes and 0xf),
            blockSize1 = 1 shl (sizes shr 4),
            codebooks = (data[1].toInt() and 0xff) + 1
        )
    }

    const val HEADER = 20

    private fun int(data: ByteArray, position: Int): Int =
        ((data[position].toInt() and 0xff) shl 24) or ((data[position + 1].toInt() and 0xff) shl 16) or
            ((data[position + 2].toInt() and 0xff) shl 8) or (data[position + 3].toInt() and 0xff)
}
