package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.Parameterized

data class StructDefinition(
    override var id: Int = -1,
    override var params: Map<Int, Any>? = null,
) : Definition, Parameterized, OpcodeOrdered {

    var paramRecords: List<KeyedValue>? = null

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null


    @JvmOverloads
    fun getIntValue(key: Int, default: Int = 0): Int = params?.get(key) as? Int ?: default

    fun getStringValue(key: Int): String? = params?.get(key) as? String

    fun getValues(): Map<Int, Any> = params ?: emptyMap()

    companion object {
        val EMPTY = StructDefinition()
    }
}
