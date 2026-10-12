package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class EnumDefinition(
    override var id: Int = -1,
    var keyType: Char = 0.toChar(),
    var valueType: Char = 0.toChar(),
    var defaultString: String = "null",
    var defaultInt: Int = 0,
    var length: Int = 0,
    var map: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    var arraySize: Int = -1,
    var entries: List<KeyedValue>? = null
) : Definition, Extra, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
    fun getKey(value: Any) = map?.filterValues { it == value }?.keys?.lastOrNull() ?: -1

    fun getInt(id: Int) = map?.get(id) as? Int ?: defaultInt

    fun randomInt() = map?.values?.random() as? Int ?: defaultInt

    fun getString(id: Int) = map?.get(id) as? String ?: defaultString

    fun getStringValue(id: Int) = getString(id)

    fun getIntValue(id: Int) = getInt(id)

    fun getIntValueAtIndex(id: Int) = getInt(id)

    fun getValue(id: Any): Any? = map?.get(id as? Int ?: return null)

    fun getDefaultIntValue(): Int = defaultInt

    fun getValues(): Map<Int, Any> = map ?: emptyMap()

    fun getSize(): Int = map?.size ?: 0

    fun getKeyForValue(value: Any): Int {
        val m = map ?: return -1
        for ((k, v) in m) {
            if (v == value) return k
        }
        return -1
    }

    companion object {
        val EMPTY = EnumDefinition()
    }
}