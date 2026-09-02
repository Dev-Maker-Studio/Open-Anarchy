package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.IntArrayList
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.OpcodeOrdered
import java.nio.BufferUnderflowException

abstract class DefinitionDecoder<T : Definition>(val index: Int) {

    abstract fun create(size: Int): Array<T>

    open fun load(definitions: Array<T>, reader: Reader) {
        val id = readId(reader)
        read(definitions, id, reader)
    }

    open fun readId(reader: Reader) = reader.readInt()

    /**
     * Load from cache
     */
    open fun load(cache: Cache): Array<T> {
        val start = System.currentTimeMillis()
        val size = size(cache) + 1
        val definitions = create(size)
        for (id in 0 until size) {
            try {
                load(definitions, cache, id)
            } catch (e: BufferUnderflowException) {
                logError("Error reading definition $id", e)
                throw e
            }
        }
        logInfo("$size ${this::class.simpleName} definitions loaded in ${System.currentTimeMillis() - start}ms")
        return definitions
    }

    open fun size(cache: Cache): Int {
        return cache.lastArchiveId(index) * 256 + (cache.fileCount(index, cache.lastArchiveId(index)))
    }

    open fun load(definitions: Array<T>, cache: Cache, id: Int) {
        val archive = getArchive(id)
        val file = getFile(id)
        val data = cache.data(index, archive, file) ?: return
        read(definitions, id, BufferReader(data))
    }

    open fun getFile(id: Int) = id

    open fun getArchive(id: Int) = id

    protected fun read(definitions: Array<T>, id: Int, reader: Reader) {
        val definition = definitions[id]
        readLoop(definition, reader)
        changeValues(definitions, definition)
    }

    /**
     * Reads opcodes until the 0 terminator.
     *
     * A definition that implements [OpcodeOrdered] also has the opcodes recorded on it exactly as
     * the file carried them, which is what lets its encoder reproduce the file byte for byte; see
     * [OpcodeOrdered].
     */
    open fun readLoop(definition: T, buffer: Reader) {
        val ordered = definition as? OpcodeOrdered
        if (ordered == null) {
            while (true) {
                val opcode = buffer.readUnsignedByte()
                if (opcode == 0) {
                    break
                }
                definition.read(opcode, buffer)
            }
            return
        }
        val order = IntArrayList()
        // Payload start and end offset of each opcode, two entries per opcode.
        val bounds = IntArrayList()
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            val start = buffer.position()
            definition.read(opcode, buffer)
            order.add(opcode)
            bounds.add(start)
            bounds.add(buffer.position())
        }
        val opcodes = order.toIntArray()
        ordered.opcodeOrder = opcodes
        ordered.shadowedPayloads = shadowedPayloads(buffer, opcodes, bounds)
    }

    /**
     * The payload of every opcode occurrence that a later occurrence of the same opcode overwrote
     * with a different value, so the encoder can put it back; null when no opcode repeats with a
     * different payload, which is all but a handful of files.
     *
     * @see world.gregs.voidps.cache.definition.OpcodeOrdered.shadowedPayloads
     */
    private fun shadowedPayloads(buffer: Reader, opcodes: IntArray, bounds: IntArrayList): Array<ByteArray?>? {
        val last = HashMap<Int, Int>(opcodes.size)
        var repeats = false
        for (index in opcodes.indices) {
            if (last.put(opcodes[index], index) != null) {
                repeats = true
            }
        }
        if (!repeats) {
            return null
        }
        val end = buffer.position()
        val shadowed = arrayOfNulls<ByteArray>(opcodes.size)
        var overwritten = false
        for (index in opcodes.indices) {
            val final = last.getValue(opcodes[index])
            if (final == index || sameBytes(buffer, bounds, index, final)) {
                continue
            }
            val payload = ByteArray(bounds.getInt(index * 2 + 1) - bounds.getInt(index * 2))
            buffer.position(bounds.getInt(index * 2))
            buffer.readBytes(payload)
            shadowed[index] = payload
            overwritten = true
        }
        buffer.position(end)
        return if (overwritten) shadowed else null
    }

    private fun sameBytes(buffer: Reader, bounds: IntArrayList, first: Int, second: Int): Boolean {
        val firstStart = bounds.getInt(first * 2)
        val secondStart = bounds.getInt(second * 2)
        val length = bounds.getInt(first * 2 + 1) - firstStart
        if (length != bounds.getInt(second * 2 + 1) - secondStart) {
            return false
        }
        for (offset in 0 until length) {
            buffer.position(firstStart + offset)
            val left = buffer.readByte()
            buffer.position(secondStart + offset)
            if (left != buffer.readByte()) {
                return false
            }
        }
        return true
    }

    /**
     * Reads a single opcode's payload into [this].
     *
     * Public so the cache source tree's codecs (and the encoder parity tests) can drive the opcode
     * loop themselves and record where each opcode's bytes started and ended.
     */
    abstract fun T.read(opcode: Int, buffer: Reader)

    open fun changeValues(definitions: Array<T>, definition: T) {
    }

    companion object {
        /**
         * Decodes a single cp1252 byte, rejecting the string terminator.
         *
         * @see Cp1252
         */
        fun byteToChar(b: Byte): Char {
            val i = 0xff and b.toInt()
            require(i != 0) { "Non cp1252 character 0x" + i.toString(16) + " provided" }
            return Cp1252.decode(i)
        }
    }
}