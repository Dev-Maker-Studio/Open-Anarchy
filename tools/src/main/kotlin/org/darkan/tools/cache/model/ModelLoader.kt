package org.darkan.tools.cache.model

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache

/**
 * A batch of faces sharing the same texture.
 */
data class TextureBatch(
    val mesh: Mesh,
    val textureId: Int  // -1 for untextured, material ID for textured
)

/**
 * Textured model data containing batched meshes for efficient rendering.
 * Faces are grouped by texture to minimize texture bind calls.
 */
data class TexturedModel(
    val colorBatch: Mesh?,              // Faces without textures (rendered with vertex colors)
    val textureBatches: List<TextureBatch>, // Faces grouped by texture ID
    val hasTextures: Boolean
) {
    val mesh: Mesh? get() = colorBatch  // Legacy access for non-textured rendering

    fun cleanup() {
        colorBatch?.cleanup()
        textureBatches.forEach { it.mesh.cleanup() }
    }
}

/**
 * Intermediate face data for batching.
 */
private data class FaceData(
    val v0: Vector3f, val v1: Vector3f, val v2: Vector3f,
    val color: Vector3f,
    val normal: Vector3f,
    val u0: Float, val v0Uv: Float,
    val u1: Float, val v1Uv: Float,
    val u2: Float, val v2Uv: Float
)

/**
 * Converts ModelDefinitions to renderable Meshes with texture support.
 */
object ModelLoader {
    private val definitionCache = mutableMapOf<Int, ModelDefinition?>()

    /**
     * Load a textured model. Creates new mesh objects each time since meshes
     * contain OpenGL resources that may be cleaned up by the caller.
     */
    fun loadTexturedModel(cache: Cache, modelId: Int): TexturedModel? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createTexturedModel(def)
    }

    fun loadMesh(cache: Cache, modelId: Int): Mesh? {
        return loadTexturedModel(cache, modelId)?.mesh
    }

    fun loadDefinition(cache: Cache, modelId: Int): ModelDefinition? {
        return definitionCache.getOrPut(modelId) {
            ModelDecoder.decode(cache, modelId)
        }
    }

    /**
     * Create a textured model with faces batched by texture ID for efficient rendering.
     */
    fun createTexturedModel(def: ModelDefinition): TexturedModel? {
        if (def.faceCount == 0) return null

        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        // Calculate face normals
        val faceNormals = Array(def.faceCount) { i ->
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) {
                Vector3f(0f, 1f, 0f)
            } else {
                val v0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
                val v1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
                val v2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])
                CoordinateUtil.calculateNormal(v0, v1, v2)
            }
        }

        // Group faces by texture ID (-1 for untextured)
        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()
        var hasAnyTextures = false

        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Check alpha - skip fully transparent faces
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 255
            if (alpha == 0) continue

            // Get texture ID for this face (-1 if none)
            // Note: faceTextures values are 1-indexed in cache, so 0 means no texture
            // The texture ID maps to a TextureType which defines a procedural operation DAG
            // that generates the final texture pixels from sprites, noise, gradients, etc.
            val rawTextureId = def.faceTextures?.get(i)?.toInt()?.and(0xffff) ?: 0
            val textureId = if (rawTextureId > 0 && rawTextureId != 65535) rawTextureId - 1 else -1
            if (textureId >= 0) hasAnyTextures = true

            // Get positions
            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            // Get color - use HSL color for all faces
            // For textured faces, this will be modulated by the texture
            // For untextured faces, this is the final color
            val hsl = def.faceColors[i].toInt() and 0xffff
            val color = if (hsl == 0 || hsl == 65535) {
                Vector3f(0.8f, 0.8f, 0.8f) // Default gray for invalid colors
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // UV coordinates - use actual coordinates if available, otherwise planar mapping
            val uvs = if (textureId >= 0 && def.textureCoordU != null && def.textureCoordV != null &&
                aIdx < def.textureCoordU.size && bIdx < def.textureCoordU.size && cIdx < def.textureCoordU.size) {
                // Use actual UV coordinates from model
                floatArrayOf(
                    def.textureCoordU[aIdx], def.textureCoordV[aIdx],
                    def.textureCoordU[bIdx], def.textureCoordV[bIdx],
                    def.textureCoordU[cIdx], def.textureCoordV[cIdx]
                )
            } else if (textureId >= 0) {
                // Simple planar mapping fallback
                floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, faceNormals[i],
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5])
            )
        }

        if (facesByTexture.isEmpty()) return null

        // Build mesh for untextured faces
        val colorBatch = facesByTexture[-1]?.let { faces ->
            buildMesh(faces)
        }

        // Build meshes for each texture batch
        val textureBatches = facesByTexture
            .filter { it.key != -1 }
            .map { (textureId, faces) ->
                TextureBatch(buildMesh(faces), textureId)
            }

        return TexturedModel(colorBatch, textureBatches, hasAnyTextures)
    }

    private fun buildMesh(faces: List<FaceData>): Mesh {
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (face in faces) {
            positions.addAll(listOf(face.v0.x, face.v0.y, face.v0.z))
            positions.addAll(listOf(face.v1.x, face.v1.y, face.v1.z))
            positions.addAll(listOf(face.v2.x, face.v2.y, face.v2.z))

            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))
            colors.addAll(listOf(face.color.x, face.color.y, face.color.z))

            texCoords.addAll(listOf(face.u0, face.v0Uv))
            texCoords.addAll(listOf(face.u1, face.v1Uv))
            texCoords.addAll(listOf(face.u2, face.v2Uv))

            normals.addAll(listOf(face.normal.x, face.normal.y, face.normal.z))
            normals.addAll(listOf(face.normal.x, face.normal.y, face.normal.z))
            normals.addAll(listOf(face.normal.x, face.normal.y, face.normal.z))

            indices.add(vertexIndex)
            indices.add(vertexIndex + 1)
            indices.add(vertexIndex + 2)
            vertexIndex += 3
        }

        return Mesh(
            positions.toFloatArray(),
            colors.toFloatArray(),
            texCoords.toFloatArray(),
            normals.toFloatArray(),
            indices.toIntArray()
        )
    }

    /**
     * Create mesh without texture support (legacy method).
     */
    fun createMesh(def: ModelDefinition): Mesh? {
        return createTexturedModel(def)?.mesh
    }

    fun clearCache() {
        definitionCache.clear()
    }

    fun getCachedDefinition(modelId: Int): ModelDefinition? = definitionCache[modelId]
}
