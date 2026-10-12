package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Gltf {

    const val GENERATOR = "darkan cache-source"


    const val BYTE = 5120
    const val UNSIGNED_BYTE = 5121
    const val SHORT = 5122
    const val UNSIGNED_SHORT = 5123
    const val UNSIGNED_INT = 5125
    const val FLOAT = 5126


    const val ARRAY_BUFFER = 34962
    const val ELEMENT_ARRAY_BUFFER = 34963


    const val TRIANGLES = 4

    const val POINTS = 0

    const val MAX_SHORT_INDEX = 65534

    private const val MAGIC = 0x46546C67

    private const val VERSION = 2

    private const val CHUNK_JSON = 0x4E4F534A

    private const val CHUNK_BIN = 0x004E4942

    private const val HEADER_BYTES = 12

    private const val CHUNK_HEADER_BYTES = 8

    private const val SPACE: Byte = 0x20

    private const val ZERO: Byte = 0

    private val parser = Json { ignoreUnknownKeys = true }


    fun assemble(asset: String, binary: ByteArray): ByteArray {
        val text = asset.toByteArray(Charsets.UTF_8)
        val jsonPadding = padding(text.size)
        val binaryPadding = if (binary.isEmpty()) 0 else padding(binary.size)
        var length = HEADER_BYTES + CHUNK_HEADER_BYTES + text.size + jsonPadding
        if (binary.isNotEmpty()) {
            length += CHUNK_HEADER_BYTES + binary.size + binaryPadding
        }
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        out.putInt(length)
        out.putInt(text.size + jsonPadding)
        out.putInt(CHUNK_JSON)
        out.put(text)
        for (pad in 0 until jsonPadding) {
            out.put(SPACE)
        }
        if (binary.isNotEmpty()) {
            out.putInt(binary.size + binaryPadding)
            out.putInt(CHUNK_BIN)
            out.put(binary)
            for (pad in 0 until binaryPadding) {
                out.put(ZERO)
            }
        }
        return out.array()
    }

    private fun padding(size: Int): Int = (4 - (size and 3)) and 3

    inline fun scalars(out: StringBuilder, name: String, count: Int, value: (Int) -> Int) {
        out.append(",\"").append(name).append("\":[")
        for (index in 0 until count) {
            if (index != 0) {
                out.append(',')
            }
            out.append(value(index))
        }
        out.append(']')
    }

    inline fun tuples(out: StringBuilder, name: String, count: Int, arity: Int, value: (Int, Int) -> Int) {
        out.append(",\"").append(name).append("\":[")
        for (index in 0 until count) {
            if (index != 0) {
                out.append(',')
            }
            out.append('[')
            for (part in 0 until arity) {
                if (part != 0) {
                    out.append(',')
                }
                out.append(value(index, part))
            }
            out.append(']')
        }
        out.append(']')
    }


    fun chunks(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES && buffer.getInt(0) == MAGIC) { "Not a glb: bad magic." }
        require(buffer.getInt(4) == VERSION) { "glb version ${buffer.getInt(4)}, expected $VERSION." }
        var offset = HEADER_BYTES
        var text: ByteArray? = null
        var binary = ByteArray(0)
        val end = minOf(buffer.getInt(8), bytes.size)
        while (offset + CHUNK_HEADER_BYTES <= end) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + CHUNK_HEADER_BYTES
            require(start + length <= bytes.size) { "glb chunk at $offset runs past the file." }
            when (type) {
                CHUNK_JSON -> text = bytes.copyOfRange(start, start + length)
                CHUNK_BIN -> binary = bytes.copyOfRange(start, start + length)
            }
            offset = start + length
        }
        return (text ?: throw IllegalArgumentException("glb has no JSON chunk.")) to binary
    }

    fun text(bytes: ByteArray): String = String(chunks(bytes).first, Charsets.UTF_8)

    fun root(text: ByteArray): JsonObject = parser.parseToJsonElement(String(text, Charsets.UTF_8)).jsonObject

    fun accessor(root: JsonObject, binary: ByteArray, index: Int, components: Int): IntArray {
        val accessor = root["accessors"]!!.jsonArray[index].jsonObject
        require(accessor["sparse"] == null) { "Sparse accessor $index is not supported." }
        val count = accessor["count"]!!.jsonPrimitive.int
        val componentType = accessor["componentType"]!!.jsonPrimitive.int
        val size = componentSize(componentType)
        val view = root["bufferViews"]!!.jsonArray[accessor["bufferView"]!!.jsonPrimitive.int].jsonObject
        val stride = view["byteStride"]?.jsonPrimitive?.int ?: (size * components)
        val start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) +
            (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        val values = IntArray(count * components)
        for (element in 0 until count) {
            val offset = start + element * stride
            for (part in 0 until components) {
                values[element * components + part] = readComponent(buffer, offset + part * size, componentType)
            }
        }
        return values
    }

    private fun readComponent(buffer: ByteBuffer, offset: Int, componentType: Int): Int = when (componentType) {
        BYTE -> buffer.get(offset).toInt()
        UNSIGNED_BYTE -> buffer.get(offset).toInt() and 0xff
        SHORT -> buffer.getShort(offset).toInt()
        UNSIGNED_SHORT -> buffer.getShort(offset).toInt() and 0xffff
        UNSIGNED_INT -> buffer.getInt(offset)
        FLOAT -> Math.round(buffer.getFloat(offset))
        else -> throw IllegalArgumentException("Unknown glTF component type $componentType.")
    }

    private fun componentSize(componentType: Int): Int = when (componentType) {
        BYTE, UNSIGNED_BYTE -> 1
        SHORT, UNSIGNED_SHORT -> 2
        UNSIGNED_INT, FLOAT -> 4
        else -> throw IllegalArgumentException("Unknown glTF component type $componentType.")
    }


    fun tuples(element: JsonElement?): List<IntArray> {
        if (element == null || element is JsonNull) {
            return emptyList()
        }
        return element.jsonArray.map { item ->
            val parts = item.jsonArray
            IntArray(parts.size) { parts[it].jsonPrimitive.int }
        }
    }

    fun column(tuples: List<IntArray>, part: Int): IntArray =
        IntArray(tuples.size) { if (part < tuples[it].size) tuples[it][part] else 0 }

    fun shortColumn(tuples: List<IntArray>, part: Int): ShortArray =
        ShortArray(tuples.size) { if (part < tuples[it].size) tuples[it][part].toShort() else 0 }

    fun ints(element: JsonElement?): IntArray? {
        val array = numbers(element) ?: return null
        return IntArray(array.size) { array[it].jsonPrimitive.int }
    }

    fun shorts(element: JsonElement?): ShortArray? {
        val array = numbers(element) ?: return null
        return ShortArray(array.size) { array[it].jsonPrimitive.int.toShort() }
    }

    fun bytes(element: JsonElement?): ByteArray? {
        val array = numbers(element) ?: return null
        return ByteArray(array.size) { array[it].jsonPrimitive.int.toByte() }
    }

    fun numbers(element: JsonElement?): JsonArray? {
        if (element == null || element is JsonNull) {
            return null
        }
        return element.jsonArray
    }

    private val digits = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xff
            out[index * 2] = digits[byte shr 4]
            out[index * 2 + 1] = digits[byte and 0xf]
        }
        return String(out)
    }

    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
