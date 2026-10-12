package org.darkan.tools.engine.render

import org.darkan.tools.engine.spatial.AABB
import org.joml.Matrix4f
import org.joml.Vector3f

data class BatchKey(
    val textureId: Int,
    val atlasPageIndex: Int
) {
    companion object {
        val UNTEXTURED = BatchKey(-1, -1)
    }
}

data class TransformedGeometry(
    val positions: FloatArray,
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    val cellKey: Long = 0L,
    val bounds: FloatArray? = null
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

class GeometryTransformer {
    private val tempPos = Vector3f()
    private val tempNormal = Vector3f()
    private val normalMatrix = Matrix4f()

    fun transform(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ): TransformedGeometry {
        return transformInternal(positions, colors, textureCoords, normals, indices, modelMatrix, useInverseTranspose = true)
    }

    fun transformMatchingInstanced(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f
    ): TransformedGeometry {
        return transformInternal(positions, colors, textureCoords, normals, indices, modelMatrix, useInverseTranspose = false)
    }

    private fun transformInternal(
        positions: FloatArray,
        colors: FloatArray?,
        textureCoords: FloatArray?,
        normals: FloatArray?,
        indices: IntArray,
        modelMatrix: Matrix4f,
        useInverseTranspose: Boolean
    ): TransformedGeometry {
        val transformedPositions = FloatArray(positions.size)
        val transformedNormals = normals?.let { FloatArray(it.size) }

        if (useInverseTranspose) {
            modelMatrix.invert(normalMatrix)
            normalMatrix.transpose()
        }

        var i = 0
        while (i < positions.size) {
            tempPos.set(positions[i], positions[i + 1], positions[i + 2])
            modelMatrix.transformPosition(tempPos)
            transformedPositions[i] = tempPos.x
            transformedPositions[i + 1] = tempPos.y
            transformedPositions[i + 2] = tempPos.z
            i += 3
        }

        if (normals != null && transformedNormals != null) {
            val effectiveNormalMatrix = if (useInverseTranspose) normalMatrix else modelMatrix
            i = 0
            while (i < normals.size) {
                tempNormal.set(normals[i], normals[i + 1], normals[i + 2])
                effectiveNormalMatrix.transformDirection(tempNormal)
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
            indices = if (modelMatrix.determinant3x3() < 0f) reverseWinding(indices) else indices.copyOf()
        )
    }

    private fun reverseWinding(indices: IntArray): IntArray {
        val out = indices.copyOf()
        var t = 0
        while (t + 2 < out.size) {
            val tmp = out[t + 1]
            out[t + 1] = out[t + 2]
            out[t + 2] = tmp
            t += 3
        }
        return out
    }
}

class StaticBatcher {
    private val geometryByKey = mutableMapOf<BatchKey, MutableList<TransformedGeometry>>()
    private val builtMeshes = mutableMapOf<BatchKey, Mesh>()
    private var dirty = false

    fun addGeometry(key: BatchKey, geometry: TransformedGeometry) {
        geometryByKey.getOrPut(key) { mutableListOf() }.add(geometry)
        dirty = true
    }

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

    fun buildMeshes(): Map<BatchKey, Mesh> {
        if (!dirty && builtMeshes.isNotEmpty()) {
            return builtMeshes
        }

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

    private fun mergeGeometry(geometries: List<TransformedGeometry>): Mesh {
        val merged = mergeGeometryData(geometries)
        return Mesh(merged.positions, merged.colors, merged.texCoords, merged.normals, merged.indices)
    }

    companion object {
        fun mergeGeometryData(geometries: List<TransformedGeometry>): MergedGeometry {
        var totalVertices = 0
        var totalIndices = 0
        val hasColors = geometries.any { it.colors != null }
        val hasTexCoords = geometries.any { it.textureCoords != null }
        val hasNormals = geometries.any { it.normals != null }

        for (geo in geometries) {
            totalVertices += geo.vertexCount
            totalIndices += geo.indices.size
        }

        val positions = FloatArray(totalVertices * 3)
        val colors = if (hasColors) FloatArray(totalVertices * 3) else null
        val texCoords = if (hasTexCoords) FloatArray(totalVertices * 2) else null
        val normals = if (hasNormals) FloatArray(totalVertices * 3) else null
        val indices = IntArray(totalIndices)

        val rangeFirst = IntArray(geometries.size)
        val rangeCount = IntArray(geometries.size)
        val rangeBounds = FloatArray(geometries.size * 6)
        var ranges = 0
        var currentCell = 0L
        var currentIsOpen = false

        var posOffset = 0
        var colorOffset = 0
        var texOffset = 0
        var normalOffset = 0
        var indexOffset = 0
        var vertexOffset = 0

        for (geo in geometries) {
            if (!currentIsOpen || geo.cellKey != currentCell) {
                currentCell = geo.cellKey
                currentIsOpen = true
                rangeFirst[ranges] = indexOffset
                rangeCount[ranges] = 0
                val o = ranges * 6
                rangeBounds[o] = Float.POSITIVE_INFINITY
                rangeBounds[o + 1] = Float.POSITIVE_INFINITY
                rangeBounds[o + 2] = Float.POSITIVE_INFINITY
                rangeBounds[o + 3] = Float.NEGATIVE_INFINITY
                rangeBounds[o + 4] = Float.NEGATIVE_INFINITY
                rangeBounds[o + 5] = Float.NEGATIVE_INFINITY
                ranges++
            }
            rangeCount[ranges - 1] += geo.indices.size
            val box = geo.bounds
            if (box != null) {
                val o = (ranges - 1) * 6
                for (axis in 0..2) {
                    if (box[axis] < rangeBounds[o + axis]) rangeBounds[o + axis] = box[axis]
                    if (box[3 + axis] > rangeBounds[o + 3 + axis]) rangeBounds[o + 3 + axis] = box[3 + axis]
                }
            } else {
                val o = (ranges - 1) * 6
                rangeBounds[o] = Float.NaN
            }

            System.arraycopy(geo.positions, 0, positions, posOffset, geo.positions.size)
            posOffset += geo.positions.size

            if (colors != null) {
                if (geo.colors != null) {
                    System.arraycopy(geo.colors, 0, colors, colorOffset, geo.colors.size)
                } else {
                    for (i in 0 until geo.vertexCount * 3) {
                        colors[colorOffset + i] = 1f
                    }
                }
                colorOffset += geo.vertexCount * 3
            }

            if (texCoords != null) {
                if (geo.textureCoords != null) {
                    System.arraycopy(geo.textureCoords, 0, texCoords, texOffset, geo.textureCoords.size)
                } else {
                    for (i in 0 until geo.vertexCount * 2) {
                        texCoords[texOffset + i] = 0f
                    }
                }
                texOffset += geo.vertexCount * 2
            }

            if (normals != null) {
                if (geo.normals != null) {
                    System.arraycopy(geo.normals, 0, normals, normalOffset, geo.normals.size)
                } else {
                    for (i in 0 until geo.vertexCount) {
                        normals[normalOffset + i * 3] = 0f
                        normals[normalOffset + i * 3 + 1] = 1f
                        normals[normalOffset + i * 3 + 2] = 0f
                    }
                }
                normalOffset += geo.vertexCount * 3
            }

            for (idx in geo.indices) {
                indices[indexOffset++] = idx + vertexOffset
            }
            vertexOffset += geo.vertexCount
        }

        return MergedGeometry(
            positions, colors, texCoords, normals, indices, AABB.fromPositions(positions),
            rangeFirst = rangeFirst.copyOf(ranges),
            rangeCount = rangeCount.copyOf(ranges),
            rangeBounds = rangeBounds.copyOf(ranges * 6)
        )
        }
    }

    fun getMesh(key: BatchKey): Mesh? = builtMeshes[key]

    fun getAllMeshes(): Map<BatchKey, Mesh> = builtMeshes.toMap()

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

    fun clear() {
        for (mesh in builtMeshes.values) {
            mesh.cleanup()
        }
        builtMeshes.clear()
        geometryByKey.clear()
        dirty = false
    }

    fun cleanup() = clear()
}

class MergedGeometry(
    val positions: FloatArray,
    val colors: FloatArray?,
    val texCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    val bounds: AABB,
    val rangeFirst: IntArray = intArrayOf(0),
    val rangeCount: IntArray = intArrayOf(indices.size),
    val rangeBounds: FloatArray = FloatArray(6) { Float.NaN }
) {
    fun pack(): MeshBuffers = MeshBuffers.pack(
        positions = positions,
        colors = colors,
        texCoords = texCoords,
        normals = normals,
        indices = indices
    )
}

data class BatcherStats(
    val batchCount: Int,
    val totalObjects: Int,
    val totalVertices: Int,
    val totalTriangles: Int
)
