package org.darkan.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.darkan.core.cache.ItemDefinitions
import kotlin.jvm.Transient as JvmTransient
import kotlin.math.floor

@Serializable
data class Item @JvmOverloads constructor(
    var id: Int,
    @set:JvmName("setAmountUnchecked")
    var amount: Int = 1,
    var metadata: MutableMap<String, @Contextual Any>? = null
) : Cloneable {

    @Transient
    var slot: Int = 0

    @Transient
    @JvmTransient
    var owner: ItemSlotOwner? = null

    fun markChanged() {
        owner?.itemChanged(this)
    }

    init {
        if (amount <= 0) amount = 1
    }

    constructor(id: Int, amount: Int, amt0: Boolean) : this(id, amount) {
        if (amt0) this.amount = amount
    }

    fun withSlot(slot: Int): Item {
        this.slot = slot
        return this
    }

    fun setAmount(amount: Int): Item {
        if (amount >= 0) {
            this.amount = amount
            markChanged()
        }
        return this
    }

    fun withAmount(amount: Int): Item = setAmount(amount)

    fun withMeta(key: String, value: Any) = copy(metadata = (metadata?.toMutableMap() ?: HashMap()).apply { put(key, value) })

    val definitions get() = ItemDefinitions.getDefs(id)
    val defs get() = definitions
    val name get() = definitions.name

    private fun meta(): MutableMap<String, Any> {
        markChanged()
        return metadata ?: HashMap<String, Any>().also { metadata = it }
    }

    @JvmOverloads
    fun getMetaInt(key: String, default: Int = -1): Int = getMetaDataI(key, default)

    fun getMetaData(key: String): Any? = metadata?.get(key)

    @Suppress("UNCHECKED_CAST")
    fun <T> getMetaDataO(key: String): T? = metadata?.get(key) as? T

    @JvmOverloads
    fun getMetaDataI(key: String, default: Int = -1): Int {
        val v = metadata?.get(key) ?: return default
        return when (v) {
            is Int -> v
            is Number -> floor(v.toDouble()).toInt()
            else -> default
        }
    }

    fun addMetaData(key: String, value: Any): Item { meta()[key] = value; return this }

    @Suppress("UNCHECKED_CAST")
    fun <T> setMetaDataO(key: String, value: Any?): T? {
        val map = meta()
        val old = if (value == null) map.remove(key) else map.put(key, value)
        return old as? T
    }

    fun getMetaData(): MutableMap<String, Any>? = metadata

    fun deleteMetaData() {
        metadata = null
        markChanged()
    }

    fun containsMetaData(): Boolean = metadata != null

    fun incMetaDataI(key: String): Int {
        val newVal = getMetaDataI(key) + 1
        meta()[key] = newVal
        return newVal
    }

    fun changeId(newId: Int) {
        id = newId
        markChanged()
    }

    fun decMetaDataI(key: String): Int {
        val newVal = getMetaDataI(key) - 1
        meta()[key] = newVal
        return newVal
    }

    @JvmOverloads
    fun getMetaDataD(key: String, default: Double = 0.0): Double {
        val v = metadata?.get(key) ?: return default
        return when (v) {
            is Double -> v
            is Number -> v.toDouble()
            else -> default
        }
    }

    fun getMetaString(key: String, default: String? = null): String? = metadata?.get(key) as? String ?: default

    public override fun clone(): Item = Item(id, amount, metadata?.toMutableMap())

    override fun toString() = "Item($id, amount=$amount)"

    companion object {
        @JvmField
        val EMPTY = Item(-1, 0, true)
    }
}
