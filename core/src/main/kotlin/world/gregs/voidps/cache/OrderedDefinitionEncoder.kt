package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * An encoder that reproduces a definition file byte for byte.
 *
 * The file is a list of records, so the encode is driven by a list of opcodes rather than by a fixed sequence
 * of `if (field != default)` blocks: [OpcodeOrdered.opcodeOrder] when the file had an order of its own, and
 * the encoder's own ascending [opcodes] otherwise. Each opcode's payload comes from the fields, except where
 * a record was overwritten by a later one of the same opcode, in which case the bytes the decoder kept in
 * [OpcodeOrdered.shadowedRecords] are replayed.
 */
abstract class OrderedDefinitionEncoder<T> : DefinitionEncoder<T> where T : Definition, T : OpcodeOrdered {

    override fun Writer.encode(definition: T, members: T) {
        val order = definition.opcodeOrder ?: opcodes(definition)
        val replays = HashMap<Int, ArrayDeque<ByteArray>>()
        for (record in definition.shadowedRecords ?: emptyList()) {
            replays.getOrPut(record.opcode) { ArrayDeque() }.addLast(record.data)
        }
        val occurrences = HashMap<Int, Int>()
        for (index in order.indices) {
            val opcode = order[index]
            val occurrence = occurrences.merge(opcode, 1, Int::plus)!! - 1
            writeByte(opcode)
            if (definition.repeats(opcode) || order.lastIndexOf(opcode) == index) {
                write(opcode, definition, members, occurrence)
            } else {
                // An overwritten record: its values never reached the fields, so its bytes are replayed.
                val replay = replays[opcode]?.removeFirstOrNull()
                    ?: error("No shadowed record left for opcode $opcode of definition ${definition.id}")
                writeBytes(replay)
            }
        }
        writeByte(0)
    }

    /**
     * Every opcode this encoder writes for [definition] on its own, ascending. Shadowed records add their
     * opcode a second time so a file that repeated one still comes out with the same number of records.
     */
    fun opcodes(definition: T): IntArray {
        val present = presentOpcodes(definition)
        val shadowed = definition.shadowedRecords ?: return present
        val all = IntArray(present.size + shadowed.size)
        present.copyInto(all)
        for (index in shadowed.indices) {
            all[present.size + index] = shadowed[index].opcode
        }
        all.sort()
        return all
    }

    /** The opcodes the fields of [definition] call for, ascending, repeats of [OpcodeOrdered.repeats] included. */
    protected abstract fun presentOpcodes(definition: T): IntArray

    /**
     * Writes the payload of one record, the opcode byte already written.
     *
     * @param occurrence how many records of this opcode came before it, for the opcodes that legitimately
     * repeat one record per array element
     */
    protected abstract fun Writer.write(opcode: Int, definition: T, members: T, occurrence: Int)
}
