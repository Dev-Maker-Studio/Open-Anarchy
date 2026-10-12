package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.BodyDefinition

class BodyEncoder : OpcodeEncoder<BodyDefinition>() {

    override fun opcodes(definition: BodyDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: BodyDefinition,
        members: BodyDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> slots(definition.disabledSlots)
            3 -> writeByte(definition.anInt4506)
            4 -> writeByte(definition.anInt4504)
            5 -> slots(definition.anIntArray4501)
            6 -> slots(definition.anIntArray4507)
            else -> error("Unhandled equipment defaults opcode $opcode for definition ${definition.id}.")
        }
    }

    private fun Writer.slots(slots: IntArray?) {
        val values = slots ?: IntArray(0)
        writeByte(values.size)
        for (slot in values) {
            writeByte(slot)
        }
    }

    companion object {

        fun opcodesOf(definition: BodyDefinition): IntArray {
            val opcodes = ArrayList<Int>(5)
            opcodes.add(1)
            if (definition.anInt4506 != -1) {
                opcodes.add(3)
            }
            if (definition.anInt4504 != -1) {
                opcodes.add(4)
            }
            if (definition.anIntArray4501 != null) {
                opcodes.add(5)
            }
            if (definition.anIntArray4507 != null) {
                opcodes.add(6)
            }
            return opcodes.toIntArray()
        }
    }
}
