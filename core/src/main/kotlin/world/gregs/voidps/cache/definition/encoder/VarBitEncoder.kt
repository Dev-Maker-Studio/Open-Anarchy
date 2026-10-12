package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.VarBitDefinition

class VarBitEncoder : OpcodeEncoder<VarBitDefinition>() {

    override fun opcodes(definition: VarBitDefinition): IntArray =
        if (definition.index != 0 || definition.startBit != 0 || definition.endBit != 0) intArrayOf(1) else IntArray(0)

    override fun Writer.encodeOpcode(definition: VarBitDefinition, members: VarBitDefinition, opcode: Int, occurrence: Int) {
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
