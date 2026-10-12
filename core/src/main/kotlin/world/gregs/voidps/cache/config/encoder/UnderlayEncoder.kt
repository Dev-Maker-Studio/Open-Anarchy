package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.UnderlayDefinition

class UnderlayEncoder : OpcodeEncoder<UnderlayDefinition>() {

    override fun opcodes(definition: UnderlayDefinition): IntArray {
        val opcodes = ArrayList<Int>(5)
        if (definition.colour != 0) {
            opcodes.add(1)
        }
        if (definition.texture != -1) {
            opcodes.add(2)
        }
        if (definition.scale != 512) {
            opcodes.add(3)
        }
        if (!definition.blockShadow) {
            opcodes.add(4)
        }
        if (!definition.aBoolean2892) {
            opcodes.add(5)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: UnderlayDefinition, members: UnderlayDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeMedium(definition.colour)
            2 -> writeShort(definition.texture)
            3 -> writeShort(definition.scale shr 2)
            4, 5 -> {}
            else -> error("Unhandled underlay opcode $opcode for definition ${definition.id}")
        }
    }
}
