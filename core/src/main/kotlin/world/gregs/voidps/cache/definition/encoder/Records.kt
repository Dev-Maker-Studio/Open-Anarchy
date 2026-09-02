package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.KeyedValue

/**
 * Payloads shared by the definition encoders. Each writes one record's payload, the opcode byte already
 * written by [world.gregs.voidps.cache.OrderedDefinitionEncoder], so unlike the mixin helpers on
 * [world.gregs.voidps.cache.definition.Recolourable] and friends they never decide whether to emit at all.
 */

/** Opcodes 40 and 41: a count followed by that many source/destination short pairs. */
internal fun Writer.writeColours(original: ShortArray?, modified: ShortArray?) {
    val count = original?.size ?: 0
    writeByte(count)
    for (index in 0 until count) {
        writeShort(original!![index].toInt())
        writeShort(modified!![index].toInt())
    }
}

/** Opcode 42: a count followed by that many palette bytes. */
internal fun Writer.writeColourPalette(palette: ByteArray?) {
    val bytes = palette ?: ByteArray(0)
    writeByte(bytes.size)
    for (colour in bytes) {
        writeByte(colour.toInt())
    }
}

/**
 * Opcode 249: a count followed by that many parameters, each a string flag, a 3 byte id and either a string
 * or an int. A record with no parameters is legal and leaves the map null, so a null map writes a count of 0.
 */
internal fun Writer.writeParams(params: Map<Int, Any>?, records: List<KeyedValue>?) {
    if (records != null) {
        writeByte(records.size)
        for (record in records) {
            writeParam(record.key, record.value)
        }
        return
    }
    writeByte(params?.size ?: 0)
    for ((id, value) in params ?: emptyMap()) {
        writeParam(id, value)
    }
}

private fun Writer.writeParam(id: Int, value: Any) {
    writeByte(value is String)
    writeMedium(id)
    if (value is String) {
        writeString(value)
    } else {
        writeInt(value as Int)
    }
}
