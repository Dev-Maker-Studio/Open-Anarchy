@file:Suppress("MemberVisibilityCanBePrivate")

package org.darkan.core.map.pathfinder

data class Route(
    val coords: ArrayDeque<RouteCoordinates>,
    val alternative: Boolean,
    val success: Boolean
) : List<RouteCoordinates> by coords {
    val failed: Boolean get() = !success
}

@JvmInline
value class RouteCoordinates(val packed: Int) {

    val x: Int get() = packed and 0xFFFF
    val y: Int get() = (packed shr 16) and 0xFFFF

    constructor(x: Int, y: Int) : this((x and 0xFFFF) or ((y and 0xFFFF) shl 16))

    fun translate(xOffset: Int, yOffset: Int) = RouteCoordinates(x + xOffset, y + yOffset)
    fun translateX(offset: Int) = translate(offset, 0)
    fun translateY(offset: Int) = translate(0, offset)

    override fun toString() = "RouteCoordinates(x=$x, y=$y)"
}
