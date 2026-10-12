package org.darkan.core.map.pathfinder

@Suppress("NOTHING_TO_INLINE")
public class ZoneFlags {
    public val flags: Array<IntArray?> = arrayOfNulls(TOTAL_ZONES)

    public inline fun alloc(zoneCoords: ZoneCoords): IntArray {
        val packed = zoneCoords.packedCoords
        val current = flags[packed]
        if (current != null) return current
        val new = IntArray(ZONE_SIZE)
        flags[packed] = new
        return new
    }

    public inline fun destroy(zoneCoords: ZoneCoords) {
        flags[zoneCoords.packedCoords] = null
    }

    public inline operator fun get(x: Int, y: Int, z: Int, default: Int = -1): Int {
        val zoneCoords = ZoneCoords(x shr 3, y shr 3, z)
        val array = flags[zoneCoords.packedCoords] ?: return default
        return array[zoneLocal(x, y)]
    }

    public inline operator fun set(x: Int, y: Int, z: Int, flag: Int) {
        alloc(ZoneCoords(x shr 3, y shr 3, z))[zoneLocal(x, y)] = flag
    }

    public inline fun add(x: Int, y: Int, z: Int, flag: Int) {
        val flags = alloc(ZoneCoords(x shr 3, y shr 3, z))
        val index = zoneLocal(x, y)
        val cur = flags[index]
        flags[index] = cur or flag
    }

    public inline fun remove(x: Int, y: Int, z: Int, flag: Int) {
        val flags = alloc(ZoneCoords(x shr 3, y shr 3, z))
        val index = zoneLocal(x, y)
        val cur = flags[index]
        flags[index] = cur and flag.inv()
    }

    public inline operator fun get(absoluteCoords: AbsoluteCoords, default: Int = -1): Int {
        return get(absoluteCoords.x, absoluteCoords.y, absoluteCoords.z, default)
    }

    public inline operator fun set(absoluteCoords: AbsoluteCoords, flag: Int) {
        set(absoluteCoords.x, absoluteCoords.y, absoluteCoords.z, flag)
    }

    public inline fun add(absoluteCoords: AbsoluteCoords, flag: Int) {
        add(absoluteCoords.x, absoluteCoords.y, absoluteCoords.z, flag)
    }

    public inline fun remove(absoluteCoords: AbsoluteCoords, flag: Int) {
        remove(absoluteCoords.x, absoluteCoords.y, absoluteCoords.z, flag)
    }

    public inline fun zoneLocal(x: Int, y: Int): Int = (x and 0x7) or ((y and 0x7) shl 3)

    public companion object {
        public const val TOTAL_ZONES: Int = 2048 * 2048 * 4
        public const val ZONE_SIZE: Int = 8 * 8
    }
}
