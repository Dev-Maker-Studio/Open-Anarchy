package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.OpcodeOrdered

abstract class OpcodeEncoder<T> : DefinitionEncoder<T> where T : Definition, T : OpcodeOrdered {

    final override fun Writer.encode(definition: T, members: T) {
        val order = definition.opcodeOrder ?: opcodes(definition)
        val shadowed = definition.shadowedPayloads
        val occurrences = HashMap<Int, Int>(order.size)
        for (index in order.indices) {
            val opcode = order[index]
            val occurrence = occurrences.merge(opcode, 1, Int::plus)!! - 1
            writeByte(opcode)
            val payload = if (shadowed == null || index >= shadowed.size) null else shadowed[index]
            if (payload != null) {
                writeBytes(payload)
            } else {
                encodeOpcode(definition, members, opcode, occurrence)
            }
        }
        writeByte(0)
    }

    abstract fun opcodes(definition: T): IntArray

    protected abstract fun Writer.encodeOpcode(definition: T, members: T, opcode: Int, occurrence: Int)
}
