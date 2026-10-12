package org.darkan.tools.engine.spatial

import org.joml.Vector3f

class Octree<T>(
    private val bounds: AABB,
    private val maxDepth: Int = 6,
    private val maxObjects: Int = 8
) {
    private class Node<T>(
        val bounds: AABB,
        val depth: Int
    ) {
        var children: Array<Node<T>>? = null
        val objects = mutableListOf<Entry<T>>()

        val isLeaf: Boolean get() = children == null
    }

    private data class Entry<T>(
        val obj: T,
        val bounds: AABB
    )

    private val root = Node<T>(bounds, 0)
    private var count = 0

    fun insert(obj: T, objBounds: AABB) {
        if (!bounds.intersects(objBounds)) {
            return
        }
        insert(root, Entry(obj, objBounds))
        count++
    }

    fun insert(obj: T, center: Vector3f, radius: Float = 0f) {
        val objBounds = AABB.fromCenterExtents(center, Vector3f(radius, radius, radius))
        insert(obj, objBounds)
    }

    private fun insert(node: Node<T>, entry: Entry<T>) {
        node.children?.let { children ->
            val index = getChildIndex(node, entry.bounds)
            if (index != -1) {
                insert(children[index], entry)
                return
            }
            node.objects.add(entry)
            return
        }

        node.objects.add(entry)

        if (node.objects.size > maxObjects && node.depth < maxDepth) {
            subdivide(node)
        }
    }

    private fun subdivide(node: Node<T>) {
        val center = node.bounds.center
        val childBounds = arrayOf(
            AABB(node.bounds.min, center),
            AABB(Vector3f(center.x, node.bounds.min.y, node.bounds.min.z), Vector3f(node.bounds.max.x, center.y, center.z)),
            AABB(Vector3f(node.bounds.min.x, node.bounds.min.y, center.z), Vector3f(center.x, center.y, node.bounds.max.z)),
            AABB(Vector3f(center.x, node.bounds.min.y, center.z), Vector3f(node.bounds.max.x, center.y, node.bounds.max.z)),
            AABB(Vector3f(node.bounds.min.x, center.y, node.bounds.min.z), Vector3f(center.x, node.bounds.max.y, center.z)),
            AABB(Vector3f(center.x, center.y, node.bounds.min.z), Vector3f(node.bounds.max.x, node.bounds.max.y, center.z)),
            AABB(Vector3f(node.bounds.min.x, center.y, center.z), Vector3f(center.x, node.bounds.max.y, node.bounds.max.z)),
            AABB(center, node.bounds.max)
        )

        node.children = Array(8) { i -> Node<T>(childBounds[i], node.depth + 1) }

        val toRedistribute = node.objects.toList()
        node.objects.clear()

        for (entry in toRedistribute) {
            val index = getChildIndex(node, entry.bounds)
            if (index != -1) {
                insert(node.children!![index], entry)
            } else {
                node.objects.add(entry)
            }
        }
    }

    private fun getChildIndex(node: Node<T>, objBounds: AABB): Int {
        val center = node.bounds.center

        val xNeg = objBounds.max.x < center.x
        val xPos = objBounds.min.x >= center.x
        val yNeg = objBounds.max.y < center.y
        val yPos = objBounds.min.y >= center.y
        val zNeg = objBounds.max.z < center.z
        val zPos = objBounds.min.z >= center.z

        val xSide = when {
            xNeg -> 0
            xPos -> 1
            else -> return -1
        }
        val ySide = when {
            yNeg -> 0
            yPos -> 1
            else -> return -1
        }
        val zSide = when {
            zNeg -> 0
            zPos -> 1
            else -> return -1
        }

        return xSide + zSide * 2 + ySide * 4
    }

    fun query(frustum: Frustum): List<T> {
        val results = mutableListOf<T>()
        query(root, frustum, results)
        return results
    }

    private fun query(node: Node<T>, frustum: Frustum, results: MutableList<T>) {
        val result = frustum.testAABB(node.bounds)
        if (result == Frustum.OUTSIDE) {
            return
        }

        if (result == Frustum.INSIDE) {
            collectAll(node, results)
            return
        }

        for (entry in node.objects) {
            if (frustum.isAABBVisible(entry.bounds)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, frustum, results)
        }
    }

    private fun collectAll(node: Node<T>, results: MutableList<T>) {
        for (entry in node.objects) {
            results.add(entry.obj)
        }
        node.children?.forEach { child ->
            collectAll(child, results)
        }
    }

    fun query(aabb: AABB): List<T> {
        val results = mutableListOf<T>()
        query(root, aabb, results)
        return results
    }

    private fun query(node: Node<T>, aabb: AABB, results: MutableList<T>) {
        if (!node.bounds.intersects(aabb)) {
            return
        }

        for (entry in node.objects) {
            if (entry.bounds.intersects(aabb)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, aabb, results)
        }
    }

    fun query(sphere: BoundingSphere): List<T> {
        val aabb = AABB.fromCenterExtents(
            sphere.center,
            Vector3f(sphere.radius, sphere.radius, sphere.radius)
        )
        val candidates = query(aabb)
        val radiusSq = sphere.radius * sphere.radius

        return candidates.filter { obj ->
            true
        }
    }

    fun raycast(origin: Vector3f, direction: Vector3f, maxDistance: Float = Float.MAX_VALUE): List<T> {
        val results = mutableListOf<T>()
        val invDir = Vector3f(
            if (direction.x != 0f) 1f / direction.x else Float.MAX_VALUE,
            if (direction.y != 0f) 1f / direction.y else Float.MAX_VALUE,
            if (direction.z != 0f) 1f / direction.z else Float.MAX_VALUE
        )
        raycast(root, origin, invDir, maxDistance, results)
        return results
    }

    private fun raycast(node: Node<T>, origin: Vector3f, invDir: Vector3f, maxDist: Float, results: MutableList<T>) {
        if (!rayIntersectsAABB(origin, invDir, node.bounds, maxDist)) {
            return
        }

        for (entry in node.objects) {
            if (rayIntersectsAABB(origin, invDir, entry.bounds, maxDist)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            raycast(child, origin, invDir, maxDist, results)
        }
    }

    private fun rayIntersectsAABB(origin: Vector3f, invDir: Vector3f, aabb: AABB, maxDist: Float): Boolean {
        val t1 = (aabb.min.x - origin.x) * invDir.x
        val t2 = (aabb.max.x - origin.x) * invDir.x
        val t3 = (aabb.min.y - origin.y) * invDir.y
        val t4 = (aabb.max.y - origin.y) * invDir.y
        val t5 = (aabb.min.z - origin.z) * invDir.z
        val t6 = (aabb.max.z - origin.z) * invDir.z

        val tmin = maxOf(minOf(t1, t2), minOf(t3, t4), minOf(t5, t6))
        val tmax = minOf(maxOf(t1, t2), maxOf(t3, t4), maxOf(t5, t6))

        return tmax >= 0 && tmin <= tmax && tmin <= maxDist
    }

    fun remove(obj: T): Boolean {
        return remove(root, obj)
    }

    private fun remove(node: Node<T>, obj: T): Boolean {
        val removed = node.objects.removeIf { it.obj == obj }
        if (removed) {
            count--
            return true
        }

        node.children?.let { children ->
            for (child in children) {
                if (remove(child, obj)) {
                    return true
                }
            }
        }

        return false
    }

    fun update(obj: T, newBounds: AABB): Boolean {
        if (remove(obj)) {
            insert(obj, newBounds)
            return true
        }
        return false
    }

    fun clear() {
        clearNode(root)
        count = 0
    }

    private fun clearNode(node: Node<T>) {
        node.objects.clear()
        node.children?.forEach { clearNode(it) }
        node.children = null
    }

    fun size(): Int = count

    fun isEmpty(): Boolean = count == 0

    fun getStats(): OctreeStats {
        var nodeCount = 0
        var leafCount = 0
        var maxObjects = 0
        var totalObjects = 0
        var maxDepthReached = 0

        fun visit(node: Node<T>) {
            nodeCount++
            if (node.isLeaf) leafCount++
            if (node.objects.size > maxObjects) maxObjects = node.objects.size
            totalObjects += node.objects.size
            if (node.depth > maxDepthReached) maxDepthReached = node.depth
            node.children?.forEach { visit(it) }
        }

        visit(root)

        return OctreeStats(
            nodeCount = nodeCount,
            leafCount = leafCount,
            objectCount = count,
            maxObjectsPerNode = maxObjects,
            averageObjectsPerLeaf = if (leafCount > 0) totalObjects.toFloat() / leafCount else 0f,
            maxDepthReached = maxDepthReached
        )
    }
}

data class OctreeStats(
    val nodeCount: Int,
    val leafCount: Int,
    val objectCount: Int,
    val maxObjectsPerNode: Int,
    val averageObjectsPerLeaf: Float,
    val maxDepthReached: Int
)
