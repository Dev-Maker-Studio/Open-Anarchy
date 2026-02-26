package org.darkan.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import world.gregs.voidps.cache.Cache

@Serializable
data class Item @JvmOverloads constructor(var id: Int, var amount: Int = 1, val metadata: MutableMap<String, @Contextual Any> = mutableMapOf()) : Cloneable {

    /** Transient slot index, not serialized. Set by event dispatch to track inventory position. */
    @kotlinx.serialization.Transient
    var slot: Int = -1

    init {
        require(amount >= 0) { "Item amount must be non-negative: $amount" }
    }

    fun withSlot(slot: Int): Item {
        this.slot = slot
        return this
    }

    /** Fluent setter that modifies amount in-place and returns this. */
    fun withAmount(amount: Int): Item {
        this.amount = amount
        return this
    }
    fun withMeta(key: String, value: Any) = copy(metadata = metadata.toMutableMap().apply { put(key, value) })

    val definitions get() = Cache.items[id]
    val defs get() = definitions
    val name get() = definitions.name

    fun getMetaInt(key: String, default: Int = 0): Int {
        val v = metadata[key] ?: return default
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            else -> default
        }
    }

    fun getMetaData(key: String): Any? = metadata[key]

    @Suppress("UNCHECKED_CAST")
    fun <T> getMetaDataO(key: String): T? = metadata[key] as? T

    @JvmOverloads
    fun getMetaDataI(key: String, default: Int = 0) = getMetaInt(key, default)

    fun addMetaData(key: String, value: Any): Item { metadata[key] = value; return this }

    fun setMetaDataO(key: String, value: Any) { metadata[key] = value }

    /** Returns the metadata map, or null if empty (for legacy null-check compatibility). */
    fun getMetaData(): Map<String, Any>? = if (metadata.isEmpty()) null else metadata

    fun deleteMetaData() { metadata.clear() }

    /** Returns true if this item has any metadata entries. */
    fun containsMetaData(): Boolean = metadata.isNotEmpty()

    fun incMetaDataI(key: String): Int {
        val newVal = getMetaInt(key) + 1
        metadata[key] = newVal
        return newVal
    }

    /** Changes this item's ID in-place. */
    fun changeId(newId: Int) { id = newId }

    fun decMetaDataI(key: String): Int {
        val newVal = getMetaInt(key) - 1
        metadata[key] = newVal
        return newVal
    }

    @JvmOverloads
    fun getMetaDataD(key: String, default: Double = 0.0): Double {
        val v = metadata[key] ?: return default
        return when (v) {
            is Double -> v
            is Number -> v.toDouble()
            else -> default
        }
    }

    fun getMetaString(key: String, default: String? = null): String? = metadata[key] as? String ?: default

    /** Creates a shallow copy of this item. For Java compat (data class copy() requires all params). */
    public override fun clone(): Item = Item(id, amount, metadata.toMutableMap())

    override fun toString() = "Item($id, amount=$amount)"

    companion object {
        @JvmField
        val EMPTY = Item(id = -1, amount = 0)
    }
}
