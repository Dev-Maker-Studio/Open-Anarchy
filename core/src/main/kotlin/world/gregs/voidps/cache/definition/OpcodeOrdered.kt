package world.gregs.voidps.cache.definition

/**
 * A definition that remembers which opcodes its file carried, and in what order.
 *
 * Jagex's packer does not emit opcodes in a fixed order: the same type is written with its fields
 * in whatever order the tool that produced it walked them, so ascending opcode order reproduces
 * only some of the shipped files. Two opcodes also routinely write the same field (a colour that
 * is present but equal to the default, `fade` written either as the bare opcode 11 or as opcode 14
 * with a value), which makes presence underivable from the decoded values alone.
 *
 * [opcodeOrder] closes both gaps: the decoder records the opcodes exactly as they appeared
 * ([world.gregs.voidps.cache.DefinitionDecoder.readLoop]) and the encoder replays that list,
 * taking each payload from the fields. It is nulled again whenever the recorded list is already
 * the canonical one the encoder derives on its own
 * ([world.gregs.voidps.cache.OpcodeEncoder.normalise]), so a definition that was packed
 * canonically - and any definition authored by hand - carries no order at all.
 */
interface OpcodeOrdered {

    /**
     * The opcodes the file carried, in file order, or null when that order is the canonical one
     * the encoder would emit anyway.
     */
    var opcodeOrder: IntArray?

    /**
     * The raw payload of any opcode occurrence whose value the decode threw away, indexed to match
     * [opcodeOrder]; null when nothing was thrown away, which is all but a handful of files.
     *
     * A few shipped files carry the same opcode twice with different values - an overlay with two
     * slot bytes, a base animation set with two stand turn pairs, a map element with two background
     * colours. Only the last write survives on the definition, so the earlier ones are kept
     * verbatim and replayed in place.
     */
    var shadowedPayloads: Array<ByteArray?>?
}
