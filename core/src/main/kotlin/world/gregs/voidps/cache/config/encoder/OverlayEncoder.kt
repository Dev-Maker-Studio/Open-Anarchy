package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.ConfigEncoder
import world.gregs.voidps.cache.config.data.OverlayDefinition

/**
 * Writes an overlay (`flo`, index 2 archive 4) file.
 *
 * Opcodes 1 and 7 carry rgb that the decoder converts to packed hsl and cannot convert back, so
 * they are written from [OverlayDefinition.colourRgb] / [OverlayDefinition.blendColourRgb].
 * Opcode 11's byte is recovered from the slot value the decoder packs the id into
 * (`slot shl 8 or id`, client `FloType.postDecode`).
 */
class OverlayEncoder : ConfigEncoder<OverlayDefinition>(), OpcodeEncoder<OverlayDefinition> {

    override fun Writer.encode(definition: OverlayDefinition) = encodeOpcodes(definition)

    override fun opcodes(definition: OverlayDefinition): IntArray {
        val opcodes = ArrayList<Int>(11)
        if (definition.colourRgb != -1) {
            opcodes.add(1)
        }
        if (definition.texture != -1) {
            opcodes.add(3)
        }
        if (!definition.hideUnderlay) {
            opcodes.add(5)
        }
        if (definition.blendColourRgb != -1) {
            opcodes.add(7)
        }
        if (definition.anInt961 != 0) {
            opcodes.add(8)
        }
        if (definition.scale != 512) {
            opcodes.add(9)
        }
        if (!definition.blockShadow) {
            opcodes.add(10)
        }
        if (slot(definition) != DEFAULT_SLOT) {
            opcodes.add(11)
        }
        if (definition.underlayOverrides) {
            opcodes.add(12)
        }
        if (definition.waterColour != 1190717) {
            opcodes.add(13)
        }
        if (definition.waterScale != 64) {
            opcodes.add(14)
        }
        if (definition.waterIntensity != 127) {
            opcodes.add(16)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: OverlayDefinition, opcode: Int) {
        when (opcode) {
            1 -> writeMedium(definition.colourRgb)
            2 -> writeByte(definition.texture)
            3 -> writeShort(definition.texture)
            5 -> {}
            7 -> writeMedium(definition.blendColourRgb)
            8 -> {}
            9 -> writeShort(definition.scale shr 2)
            10 -> {}
            11 -> writeByte(slot(definition))
            12 -> {}
            13 -> writeMedium(definition.waterColour)
            14 -> writeByte(definition.waterScale shr 2)
            16 -> writeByte(definition.waterIntensity)
            else -> error("Unhandled overlay opcode $opcode for definition ${definition.id}")
        }
    }

    private fun slot(definition: OverlayDefinition) = definition.anInt3633 shr 8

    private companion object {
        /** The slot value with no opcode 11, before the decoder packs the id into the low byte. */
        private const val DEFAULT_SLOT = 8
    }
}
