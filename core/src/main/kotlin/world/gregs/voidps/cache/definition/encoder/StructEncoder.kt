package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.StructDefinition

/**
 * Writes a struct (`struct`, index 2 archive 26) file: nothing but the param opcode.
 *
 * The params keep the order they were read in, which is what makes the file byte exact -
 * [world.gregs.voidps.cache.definition.Parameterized.readParameters] decodes into an insertion
 * ordered map for exactly this reason.
 */
class StructEncoder : DefinitionEncoder<StructDefinition>, OpcodeEncoder<StructDefinition> {

    override fun Writer.encode(definition: StructDefinition) = encodeOpcodes(definition)

    override fun opcodes(definition: StructDefinition): IntArray {
        return if (definition.params == null) IntArray(0) else intArrayOf(249)
    }

    override fun Writer.encodeOpcode(definition: StructDefinition, opcode: Int) {
        when (opcode) {
            249 -> {
                val records = definition.paramRecords
                if (records != null) {
                    writeByte(records.size)
                    for (record in records) {
                        writeParam(definition.id, record.key, record.value)
                    }
                    return
                }
                val params = definition.params ?: emptyMap()
                writeByte(params.size)
                for ((key, value) in params) {
                    writeParam(definition.id, key, value)
                }
            }
            else -> error("Unhandled struct opcode $opcode for definition ${definition.id}")
        }
    }

    private fun Writer.writeParam(id: Int, key: Int, value: Any) {
        writeByte(value is String)
        writeMedium(key)
        when (value) {
            is String -> writeString(value)
            is Int -> writeInt(value)
            else -> error("Param $key of struct $id is a ${value::class.simpleName}, not an int or a string")
        }
    }
}
