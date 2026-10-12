package org.darkan.core.model

import world.gregs.voidps.type.Tile

enum class GroundItemType {
    NORMAL,
    INVISIBLE,
    FOREVER
}

class GroundItem @JvmOverloads constructor(
    item: Item,
    val tile: Tile,
    val ownerUsername: String? = null,
    val type: GroundItemType = GroundItemType.NORMAL,
    val respawnTicks: Int = 25,
    var privateTime: Int = -1,
    var deleteTime: Int = -1,
) {
    val item: Item = Item(item.id, item.amount, item.metadata)

    val sourceId: Int = ownerUsername?.hashCode() ?: 0

    var visibleToId: Int = sourceId
        private set

    val id get() = item.id
    val amount get() = item.amount

    fun setId(newId: Int) { item.id = newId }
    val definitions get() = item.definitions
    val defs get() = item.defs
    val name get() = item.name
    val metaData get() = item.metadata
    val isPrivate get() = visibleToId != 0
    val isInvisible get() = type == GroundItemType.INVISIBLE
    val isRespawn get() = type == GroundItemType.FOREVER

    fun getCreatorUsername(): String? = ownerUsername

    fun getMetaData(key: String): Any? = item.getMetaData(key)

    @JvmOverloads
    fun getMetaDataI(key: String, default: Int = -1) = item.getMetaDataI(key, default)
    fun addMetaData(key: String, value: Any) = item.addMetaData(key, value)

    fun containsMetaData(): Boolean = item.containsMetaData()

    fun setAmount(amount: Int) { item.amount = amount }

    fun removeOwner() {
        visibleToId = 0
    }

    var ticks: Int = 0
        private set

    fun tick() { ticks++ }

    fun setHiddenTime(ticks: Int) {
        privateTime = this.ticks + ticks
    }

    override fun toString() = "GroundItem($item, $tile${ownerUsername?.let { ", owner=$it" } ?: ""})"
}
