package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Key for batching geometry by material properties.
 */
data class BatchKey(
    val textureId: Int,      // -1 for untextured
    val atlasPageIndex: Int  // -1 if not using atlas
) {
    companion object {
        val UNTEXTURED = BatchKey(-1, -1)
    }
}

/**
 * Transformed geometry data ready for batching.
 */
data class TransformedGeometry(
    val positions: FloatArray,    // Already transformed to world space
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,     // Already transformed
    val indices: IntArray
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransformedGeometry) return false
        return positions.contentEquals(other.positions) &&
               indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        return result
    }
}

/**
 * Builder for transformed geometry from local-space data.
 */
class GeometryTransformer {
    private val tempPos = Vector3f()
    private val tempNormal = Vector3f()
    private val normalMatrix = Matrix4f()

    /**
     * Transform geometry by a model matrix.
     */
    fun transform(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ): TransformedGeometry {
        val transformedPositions = FloatArray(positions.size)
        val transformedNormals = normals?.let { FloatArray(it.size) }

        // Calculate normal matrix
        modelMatrix.invert(normalMatrix)
        normalMatrix.transpose()

        // Transform positions
        var i = 0
        while (i < positions.size) {
            tempPos.set(positions[i], positions[i + 1], positions[i + 2])
            modelMatrix.transformPosition(tempPos)
            transformedPositions[i] = tempPos.x
            transformedPositions[i + 1] = tempPos.y
            transformedPositions[i + 2] = tempPos.z
            i += 3
        }

        // Transform normals
        if (normals != null && transformedNormals != null) {
            i = 0
            while (i < normals.size) {
                tempNormal.set(normals[i], normals[i + 1], normals[i + 2])
                normalMatrix.transformDirection(tempNormal)
                tempNormal.normalize()
                transformedNormals[i] = tempNormal.x
                transformedNormals[i + 1] = tempNormal.y
                transformedNormals[i + 2] = tempNormal.z
                i += 3
            }
        }

        return TransformedGeometry(
            positions = transformedPositions,
            colors = colors?.copyOf(),
            textureCoords = textureCoords?.copyOf(),
            normals = transformedNormals,
            indices = indices.copyOf()
        )
    }
}

/**
 * Batches multiple static objects into single merged meshes.
 *
 * Use this for terrain, buildings, props, and other non-moving geometry.
 * Significantly reduces draw calls by combining many objects into one.
 */
class StaticBatcher {
    private val geometryByKey = mutableMapOf<BatchKey, MutableList<TransformedGeometry>>()
    private val builtMeshes = mutableMapOf<BatchKey, Mesh>()
    private var dirty = false

    /**
     * Add static geometry to a batch.
     */
    fun addGeometry(key: BatchKey, geometry: TransformedGeometry) {
        geometryByKey.getOrPut(key) { mutableListOf() }.add(geometry)
        dirty = true
    }

    /**
     * Add geometry with an object transform.
     */
    fun addGeometry(
        key: BatchKey,
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ) {
        val transformer = GeometryTransformer()
        val transformed = transformer.transform(
            positions, colors, textureCoords, normals, indices, modelMatrix
        )
        addGeometry(key, transformed)
    }

    /**
     * Build merged meshes from accumulated geometry.
     * Call this after adding all geometry before rendering.
     *
     * @return Map of batch key to merged mesh
     */
    fun buildMeshes(): Map<BatchKey, Mesh> {
        if (!dirty && builtMeshes.isNotEmpty()) {
            return builtMeshes
        }

        // Clean up old meshes
        for (mesh in builtMeshes.values) {
            mesh.cleanup()
        }
        builtMeshes.clear()

        for ((key, geometries) in geometryByKey) {
            if (geometries.isEmpty()) continue

            val mesh = mergeGeometry(geometries)
            builtMeshes[key] = mesh
        }

        dirty = false
        return builtMeshes
    }

    /**
     * Merge multiple geometries into a single mesh.
     */
    private fun mergeGeometry(geometries: List<TransformedGeometry>): Mesh {
        // Calculate total sizes
        var totalVertices = 0
        var totalIndices = 0
        val hasColors = geometries.any { it.colors != null }
        val hasTexCoords = geometries.any { it.textureCoords != null }
        val hasNormals = geometries.any { it.normals != null }

        for (geo in geometries) {
            totalVertices += geo.vertexCount
            totalIndices += geo.indices.size
        }

        // Allocate arrays
        val positions = FloatArray(totalVertices * 3)
        val colors = if (hasColors) FloatArray(totalVertices * 3) else null
        val texCoords = if (hasTexCoords) FloatArray(totalVertices * 2) else null
        val normals = if (hasNormals) FloatArray(totalVertices * 3) else null
        val indices = IntArray(totalIndices)

        // Merge
        var posOffset = 0
        var colorOffset = 0
        var texOffset = 0
        var normalOffset = 0
        var indexOffset = 0
        var vertexOffset = 0

        for (geo in geometries) {
            // Copy positions
            System.arraycopy(geo.positions, 0, positions, posOffset, geo.positions.size)
            posOffset += geo.positions.size

            // Copy colors (or fill with white if this geo has none)
            if (colors != null) {
                if (geo.colors != null) {
                    System.arraycopy(geo.colors, 0, colors, colorOffset, geo.colors.size)
                } else {
                    // Fill with white
                    for (i in 0 until geo.vertexCount * 3) {
                        colors[colorOffset + i] = 1f
                    }
                }
                colorOffset += geo.vertexCount * 3
            }

            // Copy texture coordinates
            if (texCoords != null) {
                if (geo.textureCoords != null) {
                    System.arraycopy(geo.textureCoords, 0, texCoords, texOffset, geo.textureCoords.size)
                } else {
                    // Fill with zeros
                    for (i in 0 until geo.vertexCount * 2) {
                        texCoords[texOffset + i] = 0f
                    }
                }
                texOffset += geo.vertexCount * 2
            }

            // Copy normals
            if (normals != null) {
                if (geo.normals != null) {
                    System.arraycopy(geo.normals, 0, normals, normalOffset, geo.normals.size)
                } else {
                    // Fill with up vector
                    for (i in 0 until geo.vertexCount) {
                        normals[normalOffset + i * 3] = 0f
                        normals[normalOffset + i * 3 + 1] = 1f
                        normals[normalOffset + i * 3 + 2] = 0f
                    }
                }
                normalOffset += geo.vertexCount * 3
            }

            // Copy and offset indices
            for (idx in geo.indices) {
                indices[indexOffset++] = idx + vertexOffset
            }
            vertexOffset += geo.vertexCount
        }

        return Mesh(positions, colors, texCoords, normals, indices)
    }

    /**
     * Get a built mesh for a batch key.
     */
    fun getMesh(key: BatchKey): Mesh? = builtMeshes[key]

    /**
     * Get all built meshes.
     */
    fun getAllMeshes(): Map<BatchKey, Mesh> = builtMeshes.toMap()

    /**
     * Get statistics about the batches.
     */
    fun getStats(): BatcherStats {
        var totalVertices = 0
        var totalTriangles = 0

        for (geometries in geometryByKey.values) {
            for (geo in geometries) {
                totalVertices += geo.vertexCount
                totalTriangles += geo.triangleCount
            }
        }

        return BatcherStats(
            batchCount = builtMeshes.size,
            totalObjects = geometryByKey.values.sumOf { it.size },
            totalVertices = totalVertices,
            totalTriangles = totalTriangles
        )
    }

    /**
     * Clear all geometry and meshes.
     */
    fun clear() {
        for (mesh in builtMeshes.values) {
            mesh.cleanup()
        }
        builtMeshes.clear()
        geometryByKey.clear()
        dirty = false
    }

    /**
     * Clean up GPU resources.
     */
    fun cleanup() = clear()
}

data class BatcherStats(
    val batchCount: Int,
    val totalObjects: Int,
    val totalVertices: Int,
    val totalTriangles: Int
)
