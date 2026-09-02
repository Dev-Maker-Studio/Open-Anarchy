package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.IntArrayList
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.ShadowedRecord
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

    open fun readLoop(definition: T, buffer: Reader) {
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            definition.read(opcode, buffer)
        }
    }

    /**
     * Reads a definition exactly like [readLoop] but reports every opcode and the offset its byte sat at,
     * terminator included. Lets a caller say which opcode a given offset in the file belongs to, which is
     * what the encoder parity test needs to explain a differing byte.
     */
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

    /**
     * Reads like [readLoop] while recording what the decoded fields cannot hold: the order the file listed its
     * opcodes in and the payload of every record a later record of the same opcode overwrote. Definitions with
     * a byte-exact encoder ([OpcodeOrdered]) call this instead of [readLoop] so the file can be written back
     * exactly as it was read; see `docs/cache-source.md`.
     *
     * [canonical] is the encoder's own ascending opcode list for the finished definition. When it matches what
     * the file held, no order is stored: an encoder deriving the order from the fields reproduces it anyway,
     * and a definition authored by hand needs no order at all. Comparing against the encoder rather than just
     * checking the file is sorted keeps the recording honest - anything the encoder would not emit exactly
     * keeps its explicit order.
     */
    protected fun readLoopOrdered(definition: T, buffer: Reader, canonical: (T) -> IntArray) {
        val ordered = definition as OpcodeOrdered
        val opcodes = IntArrayList()
        val starts = IntArrayList()
        val ends = IntArrayList()
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            opcodes.add(opcode)
            starts.add(buffer.position())
            definition.read(opcode, buffer)
            ends.add(buffer.position())
        }
        val order = opcodes.toIntArray()
        var shadowed: MutableList<ShadowedRecord>? = null
        for (index in order.indices) {
            val opcode = order[index]
            if (ordered.repeats(opcode) || order.lastIndexOf(opcode) == index) {
                continue
            }
            val list = shadowed ?: ArrayList<ShadowedRecord>().also { shadowed = it }
            list.add(ShadowedRecord(opcode, buffer.array().copyOfRange(starts.getInt(index), ends.getInt(index))))
        }
        ordered.shadowedRecords = shadowed
        ordered.opcodeOrder = if (order.contentEquals(canonical(definition))) null else order
    }

    protected abstract fun T.read(opcode: Int, buffer: Reader)

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