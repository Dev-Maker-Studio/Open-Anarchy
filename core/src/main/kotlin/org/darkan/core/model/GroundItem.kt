package org.darkan.core.model

import world.gregs.voidps.type.Tile

enum class GroundItemType {
    NORMAL,
    INVISIBLE,
    FOREVER
}

data class GroundItem(
    val item: Item,
    val tile: Tile,
    val ownerUsername: String? = null,
    val type: GroundItemType = GroundItemType.NORMAL,
    val respawnTicks: Int = 25,
    var privateTime: Int = -1,
    var deleteTime: Int = -1,
) {
    val id get() = item.id
    val amount get() = item.amount
    val isPrivate get() = ownerUsername != null
    val isInvisible get() = type == GroundItemType.INVISIBLE
    val isRespawn get() = type == GroundItemType.FOREVER

    var ticks: Int = 0
        private set

    fun tick() { ticks++ }

    override fun toString() = "GroundItem($item, $tile${ownerUsername?.let { ", owner=$it" } ?: ""})"
}
