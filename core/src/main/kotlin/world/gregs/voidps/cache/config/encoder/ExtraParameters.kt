package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.Parameters

/**
 * Writes the payload of the param opcode (249) for an [Extra] definition: the entry count, then
 * one `string flag / 3 byte key / value` record per entry.
 *
 * [Extra.readParameters] keys the map by param *name* when it is given a [Parameters] catalog, and
 * a name cannot be turned back into the 3 byte key. A decoder whose output is going to be encoded
 * again has to be built with [Parameters.EMPTY], which keys by the id as a decimal string; that is
 * the default for every decoder here.
 */
internal fun Writer.writeExtras(id: Int, extras: Map<String, Any>?) {
    val parameters = extras ?: emptyMap()
    writeByte(parameters.size)
    for ((key, value) in parameters) {
        val param = key.toIntOrNull()
            ?: error("Param '$key' of definition $id is a name, not an id; decode with Parameters.EMPTY to encode again")
        writeByte(value is String)
        writeMedium(param)
        when (value) {
            is String -> writeString(value)
            is Int -> writeInt(value)
            else -> error("Param $param of definition $id is a ${value::class.simpleName}, not an int or a string")
        }
    }
}
