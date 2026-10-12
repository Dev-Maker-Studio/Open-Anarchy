package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class PlayerVariableParameterDefinition(
    override var id: Int = -1,
    var type: Int = 0,
    var typeChar: Char = 0.toChar()
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
}