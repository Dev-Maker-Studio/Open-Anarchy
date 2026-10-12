package world.gregs.voidps.cache.config.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.data.MapSceneDefinition

class MapSceneEncoder : OpcodeEncoder<MapSceneDefinition>() {

    override fun opcodes(definition: MapSceneDefinition): IntArray {
        val opcodes = ArrayList<Int>(4)
        if (definition.graphic > 0) {
            opcodes.add(1)
        }
        if (definition.colour != 0) {
            opcodes.add(2)
        }
        if (definition.aBoolean1741) {
            opcodes.add(3)
        }
        if (definition.graphic == -1) {
            opcodes.add(4)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: MapSceneDefinition, members: MapSceneDefinition, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphic)
            2 -> writeMedium(definition.colour)
            3, 4 -> {}
            else -> error("Unhandled map scene opcode $opcode for definition ${definition.id}")
        }
    }
}
