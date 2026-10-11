package org.darkan.core.map.pathfinder

@Suppress("NOTHING_TO_INLINE")
internal inline fun getZoneIndex(x: Int, y: Int, z: Int): Int {
    return (y shr 3) or ((x shr 3) shl 11) or (z shl 22)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun getIndexInZone(x: Int, y: Int): Int {
    return (x and 0x7) or ((y and 0x7) shl 3)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Array<IntArray?>.zoneAt(x: Int, y: Int, z: Int): IntArray? {
    val index = getZoneIndex(x, y, z)
    return if (index < 0 || index >= size) null else this[index]
}
