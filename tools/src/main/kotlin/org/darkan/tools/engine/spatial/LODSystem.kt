package org.darkan.tools.engine.spatial

import org.darkan.tools.engine.render.Mesh
import org.joml.Vector2f
import org.joml.Vector3f
import kotlin.math.sqrt

data class LODGroup(
    val id: String,
    val center: Vector3f,
    val meshes: List<Mesh>,
    val boundingSphere: BoundingSphere
) {
    fun getMesh(lodLevel: Int): Mesh? {
        if (meshes.isEmpty()) return null
        return meshes[lodLevel.coerceIn(0, meshes.size - 1)]
    }

    val lodCount: Int get() = meshes.size
}

data class LODSelection(
    val lodGroup: LODGroup,
    val lodLevel: Int,
    val distanceSquared: Float,
    val mesh: Mesh?
)

class LODSystem(
    var lodDistances: FloatArray = floatArrayOf(50f, 150f, 400f, 1000f)
) {
    private val lodGroups = mutableMapOf<String, LODGroup>()

    private var lodDistancesSq = lodDistances.map { it * it }.toFloatArray()

    private var lastSelectionTimeNanos = 0L
    private var lodCounts = IntArray(5)

    fun setLODDistances(distances: FloatArray) {
        lodDistances = distances
        lodDistancesSq = distances.map { it * it }.toFloatArray()
    }

    fun registerLODGroup(id: String, center: Vector3f, meshes: List<Mesh>) {
        if (meshes.isEmpty()) return

        val radius = 10f

        val group = LODGroup(
            id = id,
            center = center,
            meshes = meshes,
            boundingSphere = BoundingSphere(center, radius)
        )

        lodGroups[id] = group
    }

    fun registerLODGroup(id: String, center: Vector3f, meshes: List<Mesh>, radius: Float) {
        if (meshes.isEmpty()) return

        val group = LODGroup(
            id = id,
            center = center,
            meshes = meshes,
            boundingSphere = BoundingSphere(center, radius)
        )

        lodGroups[id] = group
    }

    fun unregisterLODGroup(id: String) {
        lodGroups.remove(id)
    }

    fun getLODMesh(id: String, cameraPos: Vector3f): Mesh? {
        val group = lodGroups[id] ?: return null
        val distSq = group.center.distanceSquared(cameraPos)
        val lodLevel = calculateLODLevel(distSq)
        return group.getMesh(lodLevel)
    }

    fun selectLOD(id: String, cameraPos: Vector3f): LODSelection? {
        val group = lodGroups[id] ?: return null
        val distSq = group.center.distanceSquared(cameraPos)
        val lodLevel = calculateLODLevel(distSq)
        val mesh = group.getMesh(lodLevel)

        return LODSelection(
            lodGroup = group,
            lodLevel = lodLevel,
            distanceSquared = distSq,
            mesh = mesh
        )
    }

    private fun calculateLODLevel(distanceSquared: Float): Int {
        for ((i, thresholdSq) in lodDistancesSq.withIndex()) {
            if (distanceSquared < thresholdSq) {
                return i
            }
        }
        return lodDistancesSq.size
    }

    fun selectAllLODs(cameraPos: Vector3f): List<LODSelection> {
        val startTime = System.nanoTime()

        lodCounts.fill(0)

        val selections = mutableListOf<LODSelection>()

        for ((_, group) in lodGroups) {
            val distSq = group.center.distanceSquared(cameraPos)
            val lodLevel = calculateLODLevel(distSq)
            val mesh = group.getMesh(lodLevel)

            if (mesh != null) {
                selections.add(LODSelection(group, lodLevel, distSq, mesh))
                lodCounts[lodLevel.coerceIn(0, lodCounts.size - 1)]++
            }
        }

        lastSelectionTimeNanos = System.nanoTime() - startTime

        return selections
    }

    fun selectVisibleLODs(cameraPos: Vector3f, frustum: Frustum): List<LODSelection> {
        val startTime = System.nanoTime()

        lodCounts.fill(0)
        val selections = mutableListOf<LODSelection>()

        for ((_, group) in lodGroups) {
            if (!frustum.isSphereVisible(group.boundingSphere)) {
                continue
            }

            val distSq = group.center.distanceSquared(cameraPos)
            val lodLevel = calculateLODLevel(distSq)
            val mesh = group.getMesh(lodLevel)

            if (mesh != null) {
                selections.add(LODSelection(group, lodLevel, distSq, mesh))
                lodCounts[lodLevel.coerceIn(0, lodCounts.size - 1)]++
            }
        }

        lastSelectionTimeNanos = System.nanoTime() - startTime

        return selections
    }

    fun selectTerrainLOD(
        regionCenter: Vector2f,
        cameraPos: Vector2f,
        terrainLodDistances: FloatArray = lodDistances
    ): Int {
        val dx = regionCenter.x - cameraPos.x
        val dz = regionCenter.y - cameraPos.y
        val distSq = dx * dx + dz * dz

        for ((i, threshold) in terrainLodDistances.withIndex()) {
            if (distSq < threshold * threshold) {
                return i
            }
        }
        return terrainLodDistances.size
    }

    fun getLODGroup(id: String): LODGroup? = lodGroups[id]

    fun getAllGroupIds(): Set<String> = lodGroups.keys.toSet()

    fun getStats(): LODStats {
        return LODStats(
            totalGroups = lodGroups.size,
            selectionTimeNanos = lastSelectionTimeNanos,
            lodDistances = lodDistances.toList(),
            lodCounts = lodCounts.toList()
        )
    }

    fun clear() {
        lodGroups.clear()
        lodCounts.fill(0)
    }

    fun cleanup() {
        clear()
    }
}

data class LODStats(
    val totalGroups: Int,
    val selectionTimeNanos: Long,
    val lodDistances: List<Float>,
    val lodCounts: List<Int>
) {
    val selectionTimeMs: Float get() = selectionTimeNanos / 1_000_000f
}

object MeshSimplifier {
    fun simplify(
        positions: FloatArray,
        indices: IntArray,
        targetRatio: Float
    ): Pair<FloatArray, IntArray>? {
        if (targetRatio >= 0.9f) {
            return Pair(positions, indices)
        }

        return Pair(positions, indices)
    }
}
