package org.darkan.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class Item(val id: Int, val amount: Int = 1, val metadata: MutableMap<String, @Contextual Any> = mutableMapOf()) {

    init {
        require(amount >= 0) { "Item amount must be non-negative: $amount" }
    }

    fun withAmount(amount: Int) = copy(amount = amount)
    fun withMeta(key: String, value: Any) = copy(metadata = metadata.toMutableMap().apply { put(key, value) })

    fun getMetaInt(key: String, default: Int = 0): Int {
        val v = metadata[key] ?: return default
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            else -> default
        }
    }

    fun getMetaString(key: String, default: String? = null): String? = metadata[key] as? String ?: default

    override fun toString() = "Item($id, amount=$amount)"

    companion object {
        val EMPTY = Item(id = -1, amount = 0)
    }
}
