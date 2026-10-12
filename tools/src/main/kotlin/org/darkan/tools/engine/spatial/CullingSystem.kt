package org.darkan.tools.engine.spatial

import org.darkan.tools.engine.render.Camera
import org.joml.Vector3f

data class CullResult(
    val visibleRegions: List<RegionChunk>,
    val visibleObjects: List<CullableObject>,
    val cullingTimeNanos: Long,
    val totalRegions: Int,
    val totalObjects: Int
) {
    val visibleRegionCount: Int get() = visibleRegions.size
    val visibleObjectCount: Int get() = visibleObjects.size
    val regionCullRatio: Float get() = if (totalRegions > 0) 1f - (visibleRegionCount.toFloat() / totalRegions) else 0f
    val objectCullRatio: Float get() = if (totalObjects > 0) 1f - (visibleObjectCount.toFloat() / totalObjects) else 0f
}

data class RegionChunk(
    val regionId: Int,
    val regionX: Int,
    val regionY: Int,
    val bounds: Rect,
    val yMin: Float = -100f,
    val yMax: Float = 500f
) {
    var objectTree: Octree<CullableObject>? = null
        private set

    fun initObjectTree(worldBounds: AABB) {
        if (objectTree == null) {
            objectTree = Octree(worldBounds, maxDepth = 4, maxObjects = 16)
        }
    }
}

interface CullableObject {
    val objectId: Int
    val modelId: Int
    val bounds: AABB
    val position: Vector3f
    val regionId: Int
    val plane: Int
}

data class SceneObject(
    override val objectId: Int,
    override val modelId: Int,
    override val bounds: AABB,
    override val position: Vector3f,
    override val regionId: Int,
    override val plane: Int = 0,
    val shape: Int = 0,
    val rotation: Float = 0f,
    val scale: Vector3f = Vector3f(1f, 1f, 1f),
    val offset: Vector3f = Vector3f(0f, 0f, 0f),
    val tiltX: Float = 0f,
    val tiltZ: Float = 0f,
    val heightAdjustment: Float = 0f,
    val mirrored: Boolean = false
) : CullableObject

class CullingSystem(
    private val worldBounds: Rect = Rect(-10000f, -10000f, 10000f, 10000f),
    private val worldYMin: Float = -1000f,
    private val worldYMax: Float = 1000f
) {
    private val regionQuadtree = Quadtree<RegionChunk>(worldBounds, maxDepth = 4, maxObjects = 4)

    private val regions = mutableMapOf<Int, RegionChunk>()

    private val globalOctree = Octree<CullableObject>(
        AABB(
            Vector3f(worldBounds.minX, worldYMin, worldBounds.minZ),
            Vector3f(worldBounds.maxX, worldYMax, worldBounds.maxZ)
        ),
        maxDepth = 6,
        maxObjects = 16
    )

    private var totalRegions = 0
    private var totalObjects = 0

    fun registerRegion(chunk: RegionChunk) {
        regions[chunk.regionId] = chunk
        regionQuadtree.insert(chunk, chunk.bounds)

        val regionWorldBounds = AABB(
            Vector3f(chunk.bounds.minX, chunk.yMin, chunk.bounds.minZ),
            Vector3f(chunk.bounds.maxX, chunk.yMax, chunk.bounds.maxZ)
        )
        chunk.initObjectTree(regionWorldBounds)

        totalRegions++
    }

    fun unregisterRegion(regionId: Int) {
        val chunk = regions.remove(regionId) ?: return
        regionQuadtree.remove(chunk)
        totalRegions--
    }

    fun registerObject(obj: CullableObject) {
        val region = regions[obj.regionId]
        if (region != null && region.objectTree != null) {
            region.objectTree!!.insert(obj, obj.bounds)
        } else {
            globalOctree.insert(obj, obj.bounds)
        }
        totalObjects++
    }

    fun unregisterObject(obj: CullableObject) {
        val region = regions[obj.regionId]
        val removed = if (region != null && region.objectTree != null) {
            region.objectTree!!.remove(obj)
        } else {
            globalOctree.remove(obj)
        }
        if (removed) totalObjects--
    }

    fun cull(camera: Camera, aspectRatio: Float): CullResult {
        val startTime = System.nanoTime()

        val frustum = camera.getFrustum(aspectRatio)

        val visibleRegions = regionQuadtree.query(frustum, worldYMin, worldYMax)

        val visibleObjects = mutableListOf<CullableObject>()

        for (region in visibleRegions) {
            region.objectTree?.let { tree ->
                visibleObjects.addAll(tree.query(frustum))
            }
        }

        visibleObjects.addAll(globalOctree.query(frustum))

        val endTime = System.nanoTime()

        return CullResult(
            visibleRegions = visibleRegions,
            visibleObjects = visibleObjects,
            cullingTimeNanos = endTime - startTime,
            totalRegions = totalRegions,
            totalObjects = totalObjects
        )
    }

    fun cull(frustum: Frustum): CullResult {
        val startTime = System.nanoTime()

        val visibleRegions = regionQuadtree.query(frustum, worldYMin, worldYMax)
        val visibleObjects = mutableListOf<CullableObject>()

        for (region in visibleRegions) {
            region.objectTree?.let { tree ->
                visibleObjects.addAll(tree.query(frustum))
            }
        }

        visibleObjects.addAll(globalOctree.query(frustum))

        val endTime = System.nanoTime()

        return CullResult(
            visibleRegions = visibleRegions,
            visibleObjects = visibleObjects,
            cullingTimeNanos = endTime - startTime,
            totalRegions = totalRegions,
            totalObjects = totalObjects
        )
    }

    fun queryAABB(aabb: AABB): List<CullableObject> {
        val results = mutableListOf<CullableObject>()

        val rect = Rect(aabb.min.x, aabb.min.z, aabb.max.x, aabb.max.z)
        val affectedRegions = regionQuadtree.query(rect)

        for (region in affectedRegions) {
            region.objectTree?.let { tree ->
                results.addAll(tree.query(aabb))
            }
        }

        results.addAll(globalOctree.query(aabb))

        return results
    }

    fun raycast(origin: Vector3f, direction: Vector3f, maxDistance: Float = Float.MAX_VALUE): List<CullableObject> {
        val results = mutableListOf<CullableObject>()

        for (region in regions.values) {
            region.objectTree?.let { tree ->
                results.addAll(tree.raycast(origin, direction, maxDistance))
            }
        }

        results.addAll(globalOctree.raycast(origin, direction, maxDistance))

        return results
    }

    fun clear() {
        regionQuadtree.clear()
        regions.clear()
        globalOctree.clear()
        totalRegions = 0
        totalObjects = 0
    }

    fun getRegionAt(x: Float, z: Float): RegionChunk? {
        val results = regionQuadtree.query(Rect(x, z, x, z))
        return results.firstOrNull()
    }

    fun getStats(): CullingStats {
        return CullingStats(
            totalRegions = totalRegions,
            totalObjects = totalObjects,
            globalOctreeStats = globalOctree.getStats()
        )
    }
}

data class CullingStats(
    val totalRegions: Int,
    val totalObjects: Int,
    val globalOctreeStats: OctreeStats
)
