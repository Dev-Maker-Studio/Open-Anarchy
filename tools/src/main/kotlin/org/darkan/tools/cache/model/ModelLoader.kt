package org.darkan.tools.cache.model

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache

/**
 * Converts ModelDefinitions to renderable Meshes.
 */
object ModelLoader {
    private val modelCache = mutableMapOf<Int, Mesh?>()
    private val definitionCache = mutableMapOf<Int, ModelDefinition?>()

    fun loadMesh(cache: Cache, modelId: Int): Mesh? {
        return modelCache.getOrPut(modelId) {
            val def = loadDefinition(cache, modelId) ?: return@getOrPut null
            createMesh(def)
        }
    }

    fun loadDefinition(cache: Cache, modelId: Int): ModelDefinition? {
        return definitionCache.getOrPut(modelId) {
            ModelDecoder.decode(cache, modelId)
        }
    }

    fun createMesh(def: ModelDefinition): Mesh? {
        if (def.faceCount == 0) return null

        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        // Calculate face normals
        val faceNormals = Array(def.faceCount) { i ->
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx >= def.vertexCount || bIdx >= def.vertexCount || cIdx >= def.vertexCount) {
                Vector3f(0f, 1f, 0f)
            } else {
                val v0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
                val v1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
                val v2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])
                CoordinateUtil.calculateNormal(v0, v1, v2)
            }
        }

        // Build mesh data face by face
        var vertexIndex = 0
        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx >= def.vertexCount || bIdx >= def.vertexCount || cIdx >= def.vertexCount) {
                continue
            }

            // Get positions
            val v0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val v1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val v2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            positions.addAll(listOf(v0.x, v0.y, v0.z))
            positions.addAll(listOf(v1.x, v1.y, v1.z))
            positions.addAll(listOf(v2.x, v2.y, v2.z))

            // Get color
            val hsl = def.faceColors[i].toInt() and 0xffff
            val color = if (hsl == 0 || hsl == 65535) {
                Vector3f(0.5f, 0.5f, 0.5f) // Default gray for invalid colors
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // Apply alpha if present
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 255
            if (alpha == 0) {
                continue // Skip fully transparent faces
            }

            colors.addAll(listOf(color.x, color.y, color.z))
            colors.addAll(listOf(color.x, color.y, color.z))
            colors.addAll(listOf(color.x, color.y, color.z))

            // Normals
            val normal = faceNormals[i]
            normals.addAll(listOf(normal.x, normal.y, normal.z))
            normals.addAll(listOf(normal.x, normal.y, normal.z))
            normals.addAll(listOf(normal.x, normal.y, normal.z))

            // Indices
            indices.add(vertexIndex)
            indices.add(vertexIndex + 1)
            indices.add(vertexIndex + 2)
            vertexIndex += 3
        }

        if (positions.isEmpty()) return null

        return Mesh(
            positions.toFloatArray(),
            colors.toFloatArray(),
            null,
            normals.toFloatArray(),
            indices.toIntArray()
        )
    }

    /**
     * Create mesh with smooth normals (averaged per vertex).
     */
    fun createMeshSmooth(def: ModelDefinition): Mesh? {
        if (def.faceCount == 0) return null

        val positions = FloatArray(def.vertexCount * 3)
        val colors = FloatArray(def.vertexCount * 3)
        val normals = FloatArray(def.vertexCount * 3)
        val normalCounts = IntArray(def.vertexCount)

        // Set vertex positions
        for (i in 0 until def.vertexCount) {
            val pos = CoordinateUtil.rsToOpenGL(def.vertexX[i], def.vertexY[i], def.vertexZ[i])
            positions[i * 3] = pos.x
            positions[i * 3 + 1] = pos.y
            positions[i * 3 + 2] = pos.z
        }

        // Calculate and accumulate normals, and determine vertex colors from faces
        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx >= def.vertexCount || bIdx >= def.vertexCount || cIdx >= def.vertexCount) {
                continue
            }

            val v0 = Vector3f(positions[aIdx * 3], positions[aIdx * 3 + 1], positions[aIdx * 3 + 2])
            val v1 = Vector3f(positions[bIdx * 3], positions[bIdx * 3 + 1], positions[bIdx * 3 + 2])
            val v2 = Vector3f(positions[cIdx * 3], positions[cIdx * 3 + 1], positions[cIdx * 3 + 2])

            val faceNormal = CoordinateUtil.calculateNormal(v0, v1, v2)

            // Accumulate normal
            for (vIdx in listOf(aIdx, bIdx, cIdx)) {
                normals[vIdx * 3] += faceNormal.x
                normals[vIdx * 3 + 1] += faceNormal.y
                normals[vIdx * 3 + 2] += faceNormal.z
                normalCounts[vIdx]++
            }

            // Get color from face
            val hsl = def.faceColors[i].toInt() and 0xffff
            val color = if (hsl == 0 || hsl == 65535) {
                Vector3f(0.5f, 0.5f, 0.5f)
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // Set vertex colors (last face wins)
            for (vIdx in listOf(aIdx, bIdx, cIdx)) {
                colors[vIdx * 3] = color.x
                colors[vIdx * 3 + 1] = color.y
                colors[vIdx * 3 + 2] = color.z
            }
        }

        // Normalize normals
        for (i in 0 until def.vertexCount) {
            if (normalCounts[i] > 0) {
                val length = Math.sqrt(
                    (normals[i * 3] * normals[i * 3] +
                     normals[i * 3 + 1] * normals[i * 3 + 1] +
                     normals[i * 3 + 2] * normals[i * 3 + 2]).toDouble()
                ).toFloat()
                if (length > 0) {
                    normals[i * 3] /= length
                    normals[i * 3 + 1] /= length
                    normals[i * 3 + 2] /= length
                }
            } else {
                normals[i * 3 + 1] = 1f // Default up
            }
        }

        // Build indices
        val indices = mutableListOf<Int>()
        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx >= def.vertexCount || bIdx >= def.vertexCount || cIdx >= def.vertexCount) {
                continue
            }

            indices.add(aIdx)
            indices.add(bIdx)
            indices.add(cIdx)
        }

        if (indices.isEmpty()) return null

        return Mesh(positions, colors, null, normals, indices.toIntArray())
    }

    fun clearCache() {
        modelCache.values.forEach { it?.cleanup() }
        modelCache.clear()
        definitionCache.clear()
    }

    fun getCachedMesh(modelId: Int): Mesh? = modelCache[modelId]

    fun getCachedDefinition(modelId: Int): ModelDefinition? = definitionCache[modelId]
}
