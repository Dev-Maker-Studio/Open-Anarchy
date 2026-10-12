package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.Parameters


internal fun Writer.writeColours(original: ShortArray?, modified: ShortArray?) {
    val count = original?.size ?: 0
    writeByte(count)
    for (index in 0 until count) {
        writeShort(original!![index].toInt())
        writeShort(modified!![index].toInt())
    }
}

internal fun Writer.writeColourPalette(palette: ByteArray?) {
    val bytes = palette ?: ByteArray(0)
    writeByte(bytes.size)
    for (colour in bytes) {
        writeByte(colour.toInt())
    }
}

internal fun Writer.writeParams(id: Int, params: Map<Int, Any>?, records: List<KeyedValue>?) {
    if (records != null) {
        writeByte(records.size)
        for (record in records) {
            writeParam(id, record.key, record.value)
        }
        return
    }
    writeByte(params?.size ?: 0)
    for ((key, value) in params ?: emptyMap()) {
        writeParam(id, key, value)
    }
}

internal fun Writer.writeExtras(id: Int, extras: Map<String, Any>?) {
    val parameters = extras ?: emptyMap()
    writeByte(parameters.size)
    for ((key, value) in parameters) {
        val param = key.toIntOrNull()
            ?: error("Param '$key' of definition $id is a name, not an id; decode with Parameters.EMPTY to encode again")
        writeParam(id, param, value)
    }
}

private fun Writer.writeParam(id: Int, key: Int, value: Any) {
    writeByte(value is String)
    writeMedium(key)
    when (value) {
        is String -> writeString(value)
        is Int -> writeInt(value)
        else -> error("Param $key of definition $id is a ${value::class.simpleName}, not an int or a string")
    }
}
