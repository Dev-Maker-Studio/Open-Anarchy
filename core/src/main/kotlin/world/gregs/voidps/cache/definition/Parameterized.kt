package world.gregs.voidps.cache.definition

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.Writer

interface Parameterized {

    var params: Map<Int, Any>?

    fun readParameters(buffer: Reader) {
        val length = buffer.readUnsignedByte()
        if (length == 0) {
            return
        }
        val params = Int2ObjectArrayMap<Any>()
        for (i in 0 until length) {
            val string = buffer.readUnsignedBoolean()
            val id = buffer.readUnsignedMedium()
            params[id] = if (string) buffer.readString() else buffer.readInt()
        }
        this.params = params
    }

    /**
     * Reads an opcode 249 record into [params] and returns its parameters in file order.
     *
     * [params] keeps only the last value of each id, so a file that lists an id twice loses a record; the
     * returned list is what an encoder needs to write the record back unchanged.
     */
    fun readParameterRecords(buffer: Reader): List<KeyedValue> {
        val length = buffer.readUnsignedByte()
        if (length == 0) {
            return emptyList()
        }
        val records = ArrayList<KeyedValue>(length)
        val params = Int2ObjectArrayMap<Any>()
        for (i in 0 until length) {
            val string = buffer.readUnsignedBoolean()
            val id = buffer.readUnsignedMedium()
            val value: Any = if (string) buffer.readString() else buffer.readInt()
            records.add(KeyedValue(id, value))
            params[id] = value
        }
        this.params = params
        return records
    }

    fun writeParameters(writer: Writer) {
        params?.let { params ->
            writer.writeByte(249)
            writer.writeByte(params.size)
            params.forEach { (id, value) ->
                writer.writeByte(value is String)
                writer.writeMedium(id)
                if (value is String) {
                    writer.writeString(value)
                } else if (value is Int) {
                    writer.writeInt(value)
                }
            }
        }
    }
}