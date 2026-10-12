package world.gregs.voidps.cache.definition

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap
import world.gregs.voidps.buffer.read.Reader

interface Parameterized {

    var params: Map<Int, Any>?

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
}
