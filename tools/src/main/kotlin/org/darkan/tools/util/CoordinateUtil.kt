package org.darkan.tools.util

import org.joml.Vector3f

object CoordinateUtil {
    const val DEFAULT_SCALE = 1f / 128f

    fun rsToOpenGL(x: Int, y: Int, z: Int, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x * scale, -y * scale, -z * scale)
    }

    fun rsToOpenGL(x: Float, y: Float, z: Float, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x * scale, -y * scale, -z * scale)
    }

    fun openGLToRS(x: Float, y: Float, z: Float, scale: Float = DEFAULT_SCALE): Vector3f {
        return Vector3f(x / scale, -y / scale, -z / scale)
    }

    fun tileToWorld(tileX: Int, tileZ: Int, height: Int = 0, scale: Float = DEFAULT_SCALE): Vector3f {
        return rsToOpenGL(tileX * 512, height, tileZ * 512, scale)
    }

    fun worldToTile(pos: Vector3f, scale: Float = DEFAULT_SCALE): Pair<Int, Int> {
        val rs = openGLToRS(pos.x, pos.y, pos.z, scale)
        return Pair((rs.x / 512).toInt(), (rs.z / 512).toInt())
    }

    fun regionCenter(regionX: Int, regionY: Int, scale: Float = DEFAULT_SCALE): Vector3f {
        val centerTileX = regionX * 64 + 32
        val centerTileZ = regionY * 64 + 32
        return tileToWorld(centerTileX, centerTileZ, 0, scale)
    }

    fun getRegionId(tileX: Int, tileY: Int): Int {
        return (tileX shr 6 shl 8) or (tileY shr 6)
    }

    fun getTileFromRegion(regionId: Int, localX: Int, localY: Int): Pair<Int, Int> {
        val regionX = regionId shr 8
        val regionY = regionId and 0xff
        return Pair(regionX * 64 + localX, regionY * 64 + localY)
    }

    fun calculateNormal(v0: Vector3f, v1: Vector3f, v2: Vector3f): Vector3f {
        val edge1 = Vector3f()
        val edge2 = Vector3f()
        val normal = Vector3f()

        v1.sub(v0, edge1)
        v2.sub(v0, edge2)
        edge1.cross(edge2, normal)
        normal.normalize()

        return normal
    }
}
