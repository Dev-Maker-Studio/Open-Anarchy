package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * Writes an opcode keyed definition file: every opcode the definition carries, in the order the
 * shipped file carried them, followed by the 0 terminator.
 *
 * An encoder built on this is byte exact by construction. Which opcodes to write comes from
 * [OpcodeOrdered.opcodeOrder] when the file's order was not canonical, and from [opcodes]
 * - derived from the decoded values - when it was, or when the definition was authored by hand.
 * [encodeOpcode] only has to write one opcode's payload, never decide whether to write it.
 */
interface OpcodeEncoder<T> where T : Definition, T : OpcodeOrdered {

    /**
     * The opcodes this definition needs, ascending, derived purely from its values. This is the
     * canonical order; [normalise] drops a recorded order that already matches it.
     */
    fun opcodes(definition: T): IntArray

    /**
     * Writes the payload of a single [opcode]; the opcode byte itself is already written.
     */
    fun Writer.encodeOpcode(definition: T, opcode: Int)

    fun Writer.encodeOpcodes(definition: T) {
        val order = definition.opcodeOrder ?: opcodes(definition)
        val shadowed = definition.shadowedPayloads
        for (index in order.indices) {
            writeByte(order[index])
            val payload = if (shadowed == null || index >= shadowed.size) null else shadowed[index]
            if (payload != null) {
                writeBytes(payload)
            } else {
                encodeOpcode(definition, order[index])
            }
        }
        writeByte(0)
    }

    /**
     * Drops a recorded opcode order that is already the canonical one, so only the definitions
     * whose files were packed in some other order pay for storing it. A definition with shadowed
     * payloads keeps its order, because those are indexed against it.
     */
    fun normalise(definition: T) {
        val order = definition.opcodeOrder ?: return
        if (definition.shadowedPayloads == null && order.contentEquals(opcodes(definition))) {
            definition.opcodeOrder = null
        }
    }
}
