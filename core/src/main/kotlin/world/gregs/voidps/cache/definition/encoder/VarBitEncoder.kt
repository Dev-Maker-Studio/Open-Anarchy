package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OrderedDefinitionEncoder
import world.gregs.voidps.cache.definition.data.VarBitDefinition

/**
 * Writes an index 22 varbit file: the varp it packs into and the bit range within it.
 *
 * Every 727 varbit is one opcode 1 record or nothing at all - 1719 of the 11713 files are empty.
 */
class VarBitEncoder : OrderedDefinitionEncoder<VarBitDefinition>() {

    override fun presentOpcodes(definition: VarBitDefinition): IntArray =
        if (definition.index != 0 || definition.startBit != 0 || definition.endBit != 0) intArrayOf(1) else IntArray(0)

    override fun Writer.write(opcode: Int, definition: VarBitDefinition, members: VarBitDefinition, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeShort(definition.index)
                writeByte(definition.startBit)
                writeByte(definition.endBit)
            }
            else -> error("Unhandled varbit definition opcode: $opcode for def ${definition.id}")
        }
    }
}
