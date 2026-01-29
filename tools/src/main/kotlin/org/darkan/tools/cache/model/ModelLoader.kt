package org.darkan.tools.cache.model

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import kotlin.math.sqrt

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
 * Uses per-vertex normals for smooth shading.
 */
private data class FaceData(
    val v0: Vector3f, val v1: Vector3f, val v2: Vector3f,
    val color: Vector3f,
    val n0: Vector3f, val n1: Vector3f, val n2: Vector3f,  // Per-vertex normals
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
     * Accumulated vertex normal data for smooth shading.
     */
    private class VertexNormal {
        var x: Float = 0f
        var y: Float = 0f
        var z: Float = 0f
        var magnitude: Int = 0
    }

    /**
     * Compute UV coordinates for a textured face using RS texture triangle projection.
     *
     * If faceTexturePointer == -1: use default UVs (0,1), (1,1), (0,0)
     * Otherwise: project face vertices onto texture triangle plane
     */
    private fun computeUVs(def: ModelDefinition, faceIdx: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255

        // If no texture pointer or invalid, use default UVs
        if (texturePointer == 255 || texturePointer >= def.textureTriangleCount) {
            return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        }

        // Check mapping type - only planar (type 0) is fully supported
        val mappingType = def.texturesMapping?.get(texturePointer)?.toInt()?.and(0xff) ?: 0
        if (mappingType != 0) {
            // Cylindrical mapping (types 1-3) requires additional data not yet decoded
            // Fall back to default UVs
            return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        }

        // Get texture triangle vertices
        val texFaceX = def.textureFaceX ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        val texFaceY = def.textureFaceY ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        val texFaceZ = def.textureFaceZ ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)

        val tAIdx = texFaceX[texturePointer].toInt() and 0xffff
        val tBIdx = texFaceY[texturePointer].toInt() and 0xffff
        val tCIdx = texFaceZ[texturePointer].toInt() and 0xffff

        // Validate texture triangle indices
        if (tAIdx >= def.vertexCount || tBIdx >= def.vertexCount || tCIdx >= def.vertexCount) {
            return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        }

        // Get texture triangle vertex positions (in RS coordinate space)
        val tAx = def.vertexX[tAIdx].toFloat()
        val tAy = def.vertexY[tAIdx].toFloat()
        val tAz = def.vertexZ[tAIdx].toFloat()

        // Edge vectors of texture triangle
        val e1x = def.vertexX[tBIdx].toFloat() - tAx
        val e1y = def.vertexY[tBIdx].toFloat() - tAy
        val e1z = def.vertexZ[tBIdx].toFloat() - tAz

        val e2x = def.vertexX[tCIdx].toFloat() - tAx
        val e2y = def.vertexY[tCIdx].toFloat() - tAy
        val e2z = def.vertexZ[tCIdx].toFloat() - tAz

        // Get face vertex positions relative to texture triangle origin
        val v0x = def.vertexX[aIdx].toFloat() - tAx
        val v0y = def.vertexY[aIdx].toFloat() - tAy
        val v0z = def.vertexZ[aIdx].toFloat() - tAz

        val v1x = def.vertexX[bIdx].toFloat() - tAx
        val v1y = def.vertexY[bIdx].toFloat() - tAy
        val v1z = def.vertexZ[bIdx].toFloat() - tAz

        val v2x = def.vertexX[cIdx].toFloat() - tAx
        val v2y = def.vertexY[cIdx].toFloat() - tAy
        val v2z = def.vertexZ[cIdx].toFloat() - tAz

        // Cross product of texture triangle edges (normal)
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x

        // Compute U basis vector: cross(e2, n) / dot(cross(e2, n), e1)
        var uBasisX = e2y * nz - e2z * ny
        var uBasisY = e2z * nx - e2x * nz
        var uBasisZ = e2x * ny - e2y * nx
        var uDenom = uBasisX * e1x + uBasisY * e1y + uBasisZ * e1z
        if (uDenom == 0f) uDenom = 1f
        val uScale = 1f / uDenom

        // Compute V basis vector: cross(e1, n) / dot(cross(e1, n), e2)
        var vBasisX = e1y * nz - e1z * ny
        var vBasisY = e1z * nx - e1x * nz
        var vBasisZ = e1x * ny - e1y * nx
        var vDenom = vBasisX * e2x + vBasisY * e2y + vBasisZ * e2z
        if (vDenom == 0f) vDenom = 1f
        val vScale = 1f / vDenom

        // Project each face vertex onto texture space
        val aU = (uBasisX * v0x + uBasisY * v0y + uBasisZ * v0z) * uScale
        val aV = (vBasisX * v0x + vBasisY * v0y + vBasisZ * v0z) * vScale
        val bU = (uBasisX * v1x + uBasisY * v1y + uBasisZ * v1z) * uScale
        val bV = (vBasisX * v1x + vBasisY * v1y + vBasisZ * v1z) * vScale
        val cU = (uBasisX * v2x + uBasisY * v2y + uBasisZ * v2z) * uScale
        val cV = (vBasisX * v2x + vBasisY * v2y + vBasisZ * v2z) * vScale

        return floatArrayOf(aU, aV, bU, bV, cU, cV)
    }

    /**
     * Create a textured model with faces batched by texture ID for efficient rendering.
     * Uses smooth shading with per-vertex normals calculated by accumulating face normals.
     */
    fun createTexturedModel(def: ModelDefinition): TexturedModel? {
        if (def.faceCount == 0) return null

        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        // Calculate per-vertex normals by accumulating face normals (smooth shading)
        val vertexNormals = Array(def.vertexCount) { VertexNormal() }

        // First pass: accumulate face normals at each vertex
        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Get positions in RS coordinate space for normal calculation
            val ax = def.vertexX[aIdx]
            val ay = def.vertexY[aIdx]
            val az = def.vertexZ[aIdx]
            val bx = def.vertexX[bIdx]
            val by = def.vertexY[bIdx]
            val bz = def.vertexZ[bIdx]
            val cx = def.vertexX[cIdx]
            val cy = def.vertexY[cIdx]
            val cz = def.vertexZ[cIdx]

            // Calculate face normal (cross product of edges)
            val dx1 = bx - ax
            val dy1 = by - ay
            val dz1 = bz - az
            val dx2 = cx - ax
            val dy2 = cy - ay
            val dz2 = cz - az

            var nx = (dy1 * dz2 - dz1 * dy2).toFloat()
            var ny = (dz1 * dx2 - dx1 * dz2).toFloat()
            var nz = (dx1 * dy2 - dy1 * dx2).toFloat()

            // Normalize the face normal
            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                len = 1f / len
                nx *= len
                ny *= len
                nz *= len
            }

            // Accumulate face normal at each vertex
            vertexNormals[aIdx].x += nx
            vertexNormals[aIdx].y += ny
            vertexNormals[aIdx].z += nz
            vertexNormals[aIdx].magnitude++

            vertexNormals[bIdx].x += nx
            vertexNormals[bIdx].y += ny
            vertexNormals[bIdx].z += nz
            vertexNormals[bIdx].magnitude++

            vertexNormals[cIdx].x += nx
            vertexNormals[cIdx].y += ny
            vertexNormals[cIdx].z += nz
            vertexNormals[cIdx].magnitude++
        }

        // Function to get normalized vertex normal in OpenGL space
        fun getVertexNormal(idx: Int): Vector3f {
            val vn = vertexNormals[idx]
            if (vn.magnitude == 0) return Vector3f(0f, 1f, 0f)

            // Average the accumulated normals
            var nx = vn.x / vn.magnitude
            var ny = vn.y / vn.magnitude
            var nz = vn.z / vn.magnitude

            // Normalize
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                val invLen = 1f / len
                nx *= invLen
                ny *= invLen
                nz *= invLen
            }

            // Convert to OpenGL space (Y and Z are flipped in RS)
            return Vector3f(nx, -ny, -nz)
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
            // faceTextures values are already adjusted (-1 means no texture after decoding)
            val textureId = def.faceTextures?.get(i)?.toInt() ?: -1
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

            // UV coordinates - compute from texture triangle projection
            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            // Get per-vertex normals for smooth shading
            val norm0 = getVertexNormal(aIdx)
            val norm1 = getVertexNormal(bIdx)
            val norm2 = getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
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

            // Use per-vertex normals for smooth shading
            normals.addAll(listOf(face.n0.x, face.n0.y, face.n0.z))
            normals.addAll(listOf(face.n1.x, face.n1.y, face.n1.z))
            normals.addAll(listOf(face.n2.x, face.n2.y, face.n2.z))

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
