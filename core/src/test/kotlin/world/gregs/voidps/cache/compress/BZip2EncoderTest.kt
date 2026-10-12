package world.gregs.voidps.cache.compress

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

class BZip2EncoderTest {

    @Test
    fun `an empty input compresses to the fourteen byte empty stream`() {
        val stream = BZip2Encoder.compressWithMagic(ByteArray(0))
        assertEquals(14, stream.size, "Empty stream: ${stream.toHex()}")
        assertEquals("425a6831", stream.copyOf(4).toHex(), "Header")
        assertEquals("177245385090", stream.copyOfRange(4, 10).toHex(), "Trailer")
        assertEquals("00000000", stream.copyOfRange(10, 14).toHex(), "Combined CRC")
        assertEquals(10, BZip2Encoder.compress(ByteArray(0)).size, "Stream without the magic")
    }

    @Test
    fun `unit vectors round trip through the cache decompressor`() {
        for ((name, data) in vectors()) {
            val stream = BZip2Encoder.compress(data)
            val decompressed = ByteArray(data.size)
            val length = BZIP2Compressor().decompress(decompressed, data.size, stream, 0)
            assertEquals(data.size, length, "$name: decompressed length")
            assertContentEquals(data, decompressed, "$name: decompressed bytes")
            println("$name: ${data.size} bytes -> ${stream.size} bytes")
        }
    }

    @Test
    fun `unit vectors are byte-identical to the system bzip2`() {
        if (!SystemBzip2.available) {
            println("Skipping the byte identity check; no ${SystemBzip2.BINARY} on this machine.")
            return
        }
        for ((name, data) in vectors()) {
            val expected = SystemBzip2.compress(data)
            val actual = BZip2Encoder.compressWithMagic(data)
            if (!expected.contentEquals(actual)) {
                val at = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] }
                fail(
                    "$name: ${data.size} bytes compressed to ${actual.size} bytes, " +
                        "${SystemBzip2.BINARY} produced ${expected.size}; first difference at byte $at",
                )
            }
            println("$name: ${data.size} bytes -> ${actual.size} bytes, identical to ${SystemBzip2.BINARY} -1")
        }
    }

    private fun vectors(): List<Pair<String, ByteArray>> = listOf(
        "empty" to ByteArray(0),
        "one byte" to byteArrayOf('a'.code.toByte()),
        "300 identical bytes" to ByteArray(300) { 'q'.code.toByte() },
        "5 kB of random bytes" to Random(0x727).nextBytes(5 * 1024),
        "250 kB of text and noise" to mixed(250_000),
        "one full block of text" to mixed(99_981),
        "one byte past a full block" to mixed(99_982),
        "a highly repetitive block that trips the fallback sort" to ByteArray(200_000) { (it % 3).toByte() },
    )

    private fun mixed(size: Int): ByteArray {
        val random = Random(0xda27a4)
        val words = listOf(
            "the", "cache", "container", "archive", "definition", "opcode", "buffer", "sector",
            "bzip2", "huffman", "burrows", "wheeler", "revision", "727", "darkan", "void",
        )
        val out = ByteArray(size)
        var position = 0
        while (position < size) {
            when (random.nextInt(10)) {
                0 -> {
                    val byte = random.nextInt(256).toByte()
                    val length = minOf(random.nextInt(1, 300), size - position)
                    for (i in 0 until length) {
                        out[position++] = byte
                    }
                }
                1, 2 -> {
                    val length = minOf(random.nextInt(1, 64), size - position)
                    for (i in 0 until length) {
                        out[position++] = random.nextInt(256).toByte()
                    }
                }
                else -> {
                    val word = words.random(random) + if (random.nextInt(8) == 0) "\n" else " "
                    for (byte in word.toByteArray()) {
                        if (position >= size) {
                            break
                        }
                        out[position++] = byte
                    }
                }
            }
        }
        return out
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
