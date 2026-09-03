package org.darkan.core.model

import world.gregs.voidps.type.Tile

enum class GroundItemType {
    NORMAL,
    INVISIBLE,
    FOREVER
}

data class GroundItem @JvmOverloads constructor(
    val item: Item,
    val tile: Tile,
    var ownerUsername: String? = null,
    val type: GroundItemType = GroundItemType.NORMAL,
    val respawnTicks: Int = 25,
    var privateTime: Int = -1,
    var deleteTime: Int = -1,
) {
    val id get() = item.id
    val amount get() = item.amount

    /** Legacy setter - modifies the wrapped item's ID in-place. */
    fun setId(newId: Int) { item.id = newId }
    val definitions get() = item.definitions
    val defs get() = item.defs
    val name get() = item.name
    val metaData get() = item.metadata
    val isPrivate get() = ownerUsername != null

    /**
     * Legacy compat: returns a numeric ID derived from the owner username.
     * Returns 0 if no owner.
     */
    fun getVisibleToId(): Int = ownerUsername?.hashCode() ?: 0
    val isInvisible get() = type == GroundItemType.INVISIBLE
    val isRespawn get() = type == GroundItemType.FOREVER

    /** Legacy alias for [ownerUsername]. */
    fun getCreatorUsername(): String? = ownerUsername

    fun getMetaData(key: String): Any? = item.getMetaData(key)
    fun getMetaDataI(key: String, default: Int = 0) = item.getMetaDataI(key, default)
    fun addMetaData(key: String, value: Any) = item.addMetaData(key, value)

    /** Returns true if the wrapped item has any metadata entries. */
    fun containsMetaData(): Boolean = item.containsMetaData()

    /** Sets the amount on the wrapped item. */
    fun setAmount(amount: Int) { item.amount = amount }

    /** Clears owner, making this a public ground item. */
    fun removeOwner() {
        ownerUsername = null
    }

    var ticks: Int = 0
        private set

    fun tick() { ticks++ }

    override fun toString() = "GroundItem($item, $tile${ownerUsername?.let { ", owner=$it" } ?: ""})"
}
