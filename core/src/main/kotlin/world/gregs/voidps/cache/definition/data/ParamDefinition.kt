package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class ParamDefinition(
    override var id: Int = -1,
    var type: Char = 0.toChar(),
    var defaultInt: Int = 0,
    var defaultString: String? = null,
    var autoDisable: Boolean = true,
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null


    fun isString(): Boolean = type == 's'

    companion object {
        val EMPTY = ParamDefinition()
    }
}
