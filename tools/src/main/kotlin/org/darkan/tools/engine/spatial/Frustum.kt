package org.darkan.tools.engine.spatial

import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Vector3f

class Frustum {
    private val intersection = FrustumIntersection()
    private val viewProjection = Matrix4f()
    private var dirty = true

    private val planes = Array(6) { Vector3f() }
    private val planeDistances = FloatArray(6)

    fun update(view: Matrix4f, projection: Matrix4f) {
        projection.mul(view, viewProjection)
        intersection.set(viewProjection)
        extractPlanes()
        dirty = false
    }

    fun update(viewProjection: Matrix4f) {
        this.viewProjection.set(viewProjection)
        intersection.set(viewProjection)
        extractPlanes()
        dirty = false
    }

    fun testAABB(aabb: AABB): Int {
        return intersection.intersectAab(
            aabb.min.x, aabb.min.y, aabb.min.z,
            aabb.max.x, aabb.max.y, aabb.max.z
        )
    }

    fun isAABBVisible(aabb: AABB): Boolean {
        return intersection.testAab(
            aabb.min.x, aabb.min.y, aabb.min.z,
            aabb.max.x, aabb.max.y, aabb.max.z
        )
    }

    fun isAABBVisible(boxes: FloatArray, offset: Int): Boolean {
        return intersection.testAab(
            boxes[offset], boxes[offset + 1], boxes[offset + 2],
            boxes[offset + 3], boxes[offset + 4], boxes[offset + 5]
        )
    }

    fun isSphereVisible(sphere: BoundingSphere): Boolean {
        return intersection.testSphere(
            sphere.center.x, sphere.center.y, sphere.center.z,
            sphere.radius
        )
    }

    fun isSphereVisible(x: Float, y: Float, z: Float, radius: Float): Boolean {
        return intersection.testSphere(x, y, z, radius)
    }

    fun isPointVisible(x: Float, y: Float, z: Float): Boolean {
        return intersection.testPoint(x, y, z)
    }

    fun isPointVisible(point: Vector3f): Boolean {
        return intersection.testPoint(point.x, point.y, point.z)
    }

    fun isRectVisible(rect: Rect, yMin: Float = -1000f, yMax: Float = 1000f): Boolean {
        return intersection.testAab(
            rect.minX, yMin, rect.minZ,
            rect.maxX, yMax, rect.maxZ
        )
    }

    fun distanceToPlane(planeIndex: Int, x: Float, y: Float, z: Float): Float {
        val n = planes[planeIndex]
        return n.x * x + n.y * y + n.z * z + planeDistances[planeIndex]
    }

    fun writePlanes(out: FloatArray) {
        require(out.size >= 24) { "frustum plane array needs 24 floats, got ${out.size}" }
        for (i in 0 until 6) {
            val n = planes[i]
            out[i * 4] = n.x
            out[i * 4 + 1] = n.y
            out[i * 4 + 2] = n.z
            out[i * 4 + 3] = planeDistances[i]
        }
    }

    private fun extractPlanes() {
        val m = viewProjection

        planes[0].set(m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20())
        planeDistances[0] = m.m33() + m.m30()
        normalizePlane(0)

        planes[1].set(m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20())
        planeDistances[1] = m.m33() - m.m30()
        normalizePlane(1)

        planes[2].set(m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21())
        planeDistances[2] = m.m33() + m.m31()
        normalizePlane(2)

        planes[3].set(m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21())
        planeDistances[3] = m.m33() - m.m31()
        normalizePlane(3)

        planes[4].set(m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22())
        planeDistances[4] = m.m33() + m.m32()
        normalizePlane(4)

        planes[5].set(m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22())
        planeDistances[5] = m.m33() - m.m32()
        normalizePlane(5)
    }

    private fun normalizePlane(index: Int) {
        val n = planes[index]
        val invLen = 1f / n.length()
        n.mul(invLen)
        planeDistances[index] *= invLen
    }

    companion object {
        const val OUTSIDE = FrustumIntersection.OUTSIDE
        const val INTERSECT = FrustumIntersection.INTERSECT
        const val INSIDE = FrustumIntersection.INSIDE

        const val PLANE_LEFT = 0
        const val PLANE_RIGHT = 1
        const val PLANE_BOTTOM = 2
        const val PLANE_TOP = 3
        const val PLANE_NEAR = 4
        const val PLANE_FAR = 5
    }
}
