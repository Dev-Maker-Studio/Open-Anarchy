package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.definition.data.QuickChatOptionDefinition

class QuickChatOptionEncoder : OpcodeEncoder<QuickChatOptionDefinition>() {

    override fun opcodes(definition: QuickChatOptionDefinition): IntArray = opcodesOf(definition)

    override fun Writer.encodeOpcode(
        definition: QuickChatOptionDefinition,
        members: QuickChatOptionDefinition,
        opcode: Int,
        occurrence: Int
    ) {
        when (opcode) {
            1 -> writeString(definition.optionText)
            2 -> pairs(definition.quickReplyOptions, definition.navigateChars)
            3 -> pairs(definition.dynamicData, definition.staticData)
            4 -> {}
            else -> error("Unhandled quick chat option opcode $opcode for definition ${definition.id}.")
        }
    }

    private fun Writer.pairs(ids: IntArray?, hotkeys: CharArray?) {
        val values = ids ?: IntArray(0)
        writeByte(values.size)
        for (index in values.indices) {
            writeShort(values[index])
            val hotkey = hotkeys?.getOrNull(index) ?: ' '
            writeByte(if (hotkey.code == 0) 0 else Cp1252.encode(hotkey).toInt())
        }
    }

    companion object {

        fun opcodesOf(definition: QuickChatOptionDefinition): IntArray {
            val opcodes = ArrayList<Int>(4)
            if (definition.optionText != null) {
                opcodes.add(1)
            }
            if (definition.quickReplyOptions != null) {
                opcodes.add(2)
            }
            if (definition.dynamicData != null) {
                opcodes.add(3)
            }
            if (definition.unusedFlag4) {
                opcodes.add(4)
            }
            return opcodes.toIntArray()
        }
    }
}
