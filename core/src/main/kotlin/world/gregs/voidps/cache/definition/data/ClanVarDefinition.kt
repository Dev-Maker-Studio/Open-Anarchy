package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.cs2.CS2Type

data class ClanVarDefinition(
    override var id: Int = -1,
    var type: CS2Type? = null,
    var typeChar: Char = 0.toChar(),
    var baseVar: Int = -1,
    var startBit: Int = 0,
    var endBit: Int = 0
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null


    val maxSize: Long
        get() = if (baseVar == -1) {
            when (type) {
                CS2Type.INT -> Int.MAX_VALUE.toLong()
                CS2Type.LONG -> Long.MAX_VALUE
                else -> -1L
            }
        } else {
            1L shl (endBit - startBit)
        }
}
