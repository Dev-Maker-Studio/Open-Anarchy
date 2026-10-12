package org.darkan.core.map.pathfinder

@JvmInline
public value class ZoneCoords(public val packedCoords: Int) {
    public constructor(
        x: Int,
        y: Int,
        z: Int,
    ) : this((y and 0x7FF) or ((x and 0x7FF) shl 11) or ((z and 0x3) shl 22))

    public val x: Int
        get() = (packedCoords shr 11) and 0x7FF
    public val y: Int
        get() = packedCoords and 0x7FF
    public val z: Int
        get() = (packedCoords shr 22) and 0x3

    public fun toAbsoluteCoords(): AbsoluteCoords = AbsoluteCoords(x shl 3, y shl 3, z)
    override fun toString(): String = "ZoneCoords($x, $y, $z)"
}
