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

    open fun load(cache: Cache): Array<T> {
        val start = System.currentTimeMillis()
        val size = (size(cache) + 1).coerceAtLeast(0)
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
        val shadowed = shadowedPayloads(ordered, buffer, opcodes, bounds)
        ordered.shadowedPayloads = shadowed
        ordered.opcodeOrder = if (shadowed == null && opcodes.contentEquals(canonicalOpcodes(definition))) null else opcodes
    }

    protected open fun canonicalOpcodes(definition: T): IntArray? = null

    fun readLoopTraced(definition: T, buffer: Reader, onOpcode: (opcode: Int, position: Int) -> Unit) {
        while (true) {
            val position = buffer.position()
            val opcode = buffer.readUnsignedByte()
            onOpcode(opcode, position)
            if (opcode == 0) {
                break
            }
            definition.read(opcode, buffer)
        }
    }

    private fun shadowedPayloads(ordered: OpcodeOrdered, buffer: Reader, opcodes: IntArray, bounds: IntArrayList): Array<ByteArray?>? {
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
            val opcode = opcodes[index]
            if (ordered.repeats(opcode) || last.getValue(opcode) == index) {
                continue
            }
            val payload = payload(buffer, bounds, index)
            if (payload.contentEquals(payload(buffer, bounds, last.getValue(opcode)))) {
                continue
            }
            shadowed[index] = payload
            overwritten = true
        }
        buffer.position(end)
        return if (overwritten) shadowed else null
    }

    private fun payload(buffer: Reader, bounds: IntArrayList, index: Int): ByteArray {
        val start = bounds.getInt(index * 2)
        val payload = ByteArray(bounds.getInt(index * 2 + 1) - start)
        buffer.position(start)
        buffer.readBytes(payload)
        return payload
    }

    protected abstract fun T.read(opcode: Int, buffer: Reader)

    open fun changeValues(definitions: Array<T>, definition: T) {
    }

    companion object {
        fun byteToChar(b: Byte): Char {
            val i = 0xff and b.toInt()
            require(i != 0) { "Non cp1252 character 0x" + i.toString(16) + " provided" }
            return Cp1252.decode(i)
        }
    }
}
