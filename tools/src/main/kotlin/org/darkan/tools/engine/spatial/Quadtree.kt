package org.darkan.tools.engine.spatial

class Quadtree<T>(
    private val bounds: Rect,
    private val maxDepth: Int = 6,
    private val maxObjects: Int = 8
) {
    private class Node<T>(
        val bounds: Rect,
        val depth: Int
    ) {
        var children: Array<Node<T>>? = null
        val objects = mutableListOf<Entry<T>>()

        val isLeaf: Boolean get() = children == null
    }

    private data class Entry<T>(
        val obj: T,
        val bounds: Rect
    )

    private val root = Node<T>(bounds, 0)
    private var count = 0

    fun insert(obj: T, objBounds: Rect) {
        insert(root, Entry(obj, objBounds))
        count++
    }

    fun insert(obj: T, x: Float, z: Float) {
        insert(obj, Rect(x, z, x, z))
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
        val childBounds = node.bounds.subdivide()
        node.children = Array(4) { i -> Node<T>(childBounds[i], node.depth + 1) }

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

    private fun getChildIndex(node: Node<T>, objBounds: Rect): Int {
        val midX = node.bounds.centerX
        val midZ = node.bounds.centerZ

        val left = objBounds.maxX < midX
        val right = objBounds.minX >= midX
        val bottom = objBounds.maxZ < midZ
        val top = objBounds.minZ >= midZ

        return when {
            left && bottom -> 0
            right && bottom -> 1
            left && top -> 2
            right && top -> 3
            else -> -1
        }
    }

    fun query(frustum: Frustum, yMin: Float = -1000f, yMax: Float = 1000f): List<T> {
        val results = mutableListOf<T>()
        query(root, frustum, yMin, yMax, results)
        return results
    }

    private fun query(node: Node<T>, frustum: Frustum, yMin: Float, yMax: Float, results: MutableList<T>) {
        if (!frustum.isRectVisible(node.bounds, yMin, yMax)) {
            return
        }

        for (entry in node.objects) {
            if (frustum.isRectVisible(entry.bounds, yMin, yMax)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, frustum, yMin, yMax, results)
        }
    }

    fun query(rect: Rect): List<T> {
        val results = mutableListOf<T>()
        query(root, rect, results)
        return results
    }

    private fun query(node: Node<T>, rect: Rect, results: MutableList<T>) {
        if (!node.bounds.intersects(rect)) {
            return
        }

        for (entry in node.objects) {
            if (entry.bounds.intersects(rect)) {
                results.add(entry.obj)
            }
        }

        node.children?.forEach { child ->
            query(child, rect, results)
        }
    }

    fun queryRadius(centerX: Float, centerZ: Float, radius: Float): List<T> {
        val searchRect = Rect(
            centerX - radius, centerZ - radius,
            centerX + radius, centerZ + radius
        )
        val candidates = query(searchRect)
        val radiusSq = radius * radius

        return candidates.filter { obj ->
            true
        }
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
}
