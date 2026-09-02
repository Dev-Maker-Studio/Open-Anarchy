package world.gregs.voidps.cache.definition

/**
 * A definition whose file its encoder reproduces byte for byte.
 *
 * Jagex's packer did not write a definition's opcodes in any fixed order: measured over the whole 727 cache
 * only 1917 of 73893 locs, 155 of 15662 npcs and 7417 of 25355 objs are in ascending opcode order, and a file
 * may hold the same opcode twice with different payloads (the decoder keeps the last, as the client does).
 * Neither is recoverable from the decoded fields, so the two are recorded here.
 */
interface OpcodeOrdered {

    /**
     * Every opcode the file held, in the order it held them, repeats included. Null when that is exactly the
     * ascending order the encoder emits on its own, which is both the common shape and the one an
     * author writing a definition by hand gets for free.
     */
    var opcodeOrder: IntArray?

    /**
     * The payload of every record a later record of the same opcode overwrote, in file order. The surviving
     * record is written from the fields; these are replayed verbatim because their values are gone.
     */
    var shadowedRecords: List<ShadowedRecord>?

    /**
     * Whether [opcode] is a record type that legitimately appears once per array element rather than a field
     * written twice, and so is written from the fields on every occurrence instead of being shadowed.
     */
    fun repeats(opcode: Int): Boolean = false
}

/**
 * The raw payload of a definition record whose value a later record of the same opcode replaced.
 *
 * @param opcode the record's opcode
 * @param data the record's payload, the opcode byte itself excluded
 */
class ShadowedRecord(val opcode: Int, val data: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowedRecord) return false
        return opcode == other.opcode && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * opcode + data.contentHashCode()

    override fun toString(): String = "ShadowedRecord(opcode=$opcode, data=${data.contentToString()})"
}
