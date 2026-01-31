package org.darkan.tools.cache.model

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
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
     * Texture bounding box data for a material - used to compute normalized UVs
     * that are consistent across all faces using the same texture.
     */
    private data class TextureBounds(
        val minX: Int, val maxX: Int,
        val minY: Int, val maxY: Int,
        val minZ: Int, val maxZ: Int
    ) {
        val rangeX: Float get() = (maxX - minX).coerceAtLeast(1).toFloat()
        val rangeY: Float get() = (maxY - minY).coerceAtLeast(1).toFloat()
        val rangeZ: Float get() = (maxZ - minZ).coerceAtLeast(1).toFloat()
    }

    /**
     * Compute bounding box for all faces using a given texture ID.
     * This allows us to compute normalized UVs that span [0,1] across the texture region.
     */
    private fun computeTextureBounds(def: ModelDefinition, textureId: Int): TextureBounds {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE

        for (i in 0 until def.faceCount) {
            val faceTexId = def.faceTextures?.get(i)?.toInt() ?: -1
            if (faceTexId != textureId) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            for (idx in listOf(aIdx, bIdx, cIdx)) {
                if (idx < 0 || idx >= def.vertexCount) continue
                minX = minOf(minX, def.vertexX[idx])
                maxX = maxOf(maxX, def.vertexX[idx])
                minY = minOf(minY, def.vertexY[idx])
                maxY = maxOf(maxY, def.vertexY[idx])
                minZ = minOf(minZ, def.vertexZ[idx])
                maxZ = maxOf(maxZ, def.vertexZ[idx])
            }
        }

        // Handle empty bounds
        if (minX == Int.MAX_VALUE) {
            return TextureBounds(0, 1, 0, 1, 0, 1)
        }

        return TextureBounds(minX, maxX, minY, maxY, minZ, maxZ)
    }

    /** Cache of texture bounds per model per material */
    private val textureBoundsCache = mutableMapOf<Pair<Int, Int>, TextureBounds>()

    /**
     * Compute UV coordinates for a textured face using RS texture triangle projection.
     *
     * If faceTexturePointer == -1: use bounding-box normalized UVs (consistent across all faces)
     * Otherwise: project face vertices onto texture triangle plane
     */
    private fun computeUVs(def: ModelDefinition, faceIdx: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int = -1): FloatArray {
        val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255

        // If no texture pointer or invalid, use bounding-box normalized UVs
        // This ensures adjacent triangles sharing vertices get the same UV at those vertices
        if (texturePointer == 255 || texturePointer >= def.textureTriangleCount) {
            // Get cached bounds for this texture on this model
            val cacheKey = Pair(def.id, textureId)
            val bounds = textureBoundsCache.getOrPut(cacheKey) {
                computeTextureBounds(def, textureId)
            }

            // Project onto XY plane (horizontal plane in RS) - this maps the texture
            // across the horizontal extent of all faces using this material
            // Use XY so vertical (Y in RS = height) doesn't affect the V coordinate gradient
            val aU = (def.vertexX[aIdx] - bounds.minX) / bounds.rangeX
            val aV = (def.vertexY[aIdx] - bounds.minY) / bounds.rangeY
            val bU = (def.vertexX[bIdx] - bounds.minX) / bounds.rangeX
            val bV = (def.vertexY[bIdx] - bounds.minY) / bounds.rangeY
            val cU = (def.vertexX[cIdx] - bounds.minX) / bounds.rangeX
            val cV = (def.vertexY[cIdx] - bounds.minY) / bounds.rangeY
            return floatArrayOf(aU, aV, bU, bV, cU, cV)
        }

        // Check mapping type - only planar (type 0) is fully supported
        val mappingType = def.texturesMapping?.get(texturePointer)?.toInt()?.and(0xff) ?: 0
        if (mappingType != 0) {
            // For cylindrical/spherical/rectangular mappings without full support,
            // fall back to bounding-box normalized UVs
            val cacheKey = Pair(def.id, textureId)
            val bounds = textureBoundsCache.getOrPut(cacheKey) {
                computeTextureBounds(def, textureId)
            }

            val aU = (def.vertexX[aIdx] - bounds.minX) / bounds.rangeX
            val aV = (def.vertexY[aIdx] - bounds.minY) / bounds.rangeY
            val bU = (def.vertexX[bIdx] - bounds.minX) / bounds.rangeX
            val bV = (def.vertexY[bIdx] - bounds.minY) / bounds.rangeY
            val cU = (def.vertexX[cIdx] - bounds.minX) / bounds.rangeX
            val cV = (def.vertexY[cIdx] - bounds.minY) / bounds.rangeY
            return floatArrayOf(aU, aV, bU, bV, cU, cV)
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
        val uBasisX = e2y * nz - e2z * ny
        val uBasisY = e2z * nx - e2x * nz
        val uBasisZ = e2x * ny - e2y * nx
        var uDenom = uBasisX * e1x + uBasisY * e1y + uBasisZ * e1z
        if (uDenom == 0f) uDenom = 1f
        val uScale = 1f / uDenom

        // Compute V basis vector: cross(e1, n) / dot(cross(e1, n), e2)
        val vBasisX = e1y * nz - e1z * ny
        val vBasisY = e1z * nx - e1x * nz
        val vBasisZ = e1x * ny - e1y * nx
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

            // Check alpha - RS uses inverted alpha where:
            // - alpha = 0 means OPAQUE (fully visible)
            // - alpha = 255 means FULLY TRANSPARENT (invisible)
            // If faceAlphas is null, faces are opaque (visible)
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue  // Skip fully transparent faces

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
            // HSL value 0 is valid (black), only 65535 (0xFFFF) is a sentinel for "no color"
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f) // White for textured faces without explicit color
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            // UV coordinates - compute from texture triangle projection
            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
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

    /**
     * Create MeshData list from a model definition for instanced rendering.
     * Similar to createTexturedModel but returns raw arrays instead of GPU meshes.
     */
    fun createMeshDataList(cache: Cache, modelId: Int, debug: Boolean = false): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, if (debug) modelId else -1)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     */
    fun createMeshDataListFromDef(def: ModelDefinition, debugModelId: Int = -1): List<MeshData>? {
        if (def.faceCount == 0) return null

        // Debug output for specific models
        if (debugModelId > 0 && def.id == debugModelId) {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
            for (i in 0 until def.vertexCount) {
                minX = minOf(minX, def.vertexX[i]); maxX = maxOf(maxX, def.vertexX[i])
                minY = minOf(minY, def.vertexY[i]); maxY = maxOf(maxY, def.vertexY[i])
                minZ = minOf(minZ, def.vertexZ[i]); maxZ = maxOf(maxZ, def.vertexZ[i])
            }
            println("=== DEBUG Model $debugModelId RAW vertex bounds ===")
            println("  X: [$minX, $maxX] (range: ${maxX - minX})")
            println("  Y: [$minY, $maxY] (range: ${maxY - minY})")
            println("  Z: [$minZ, $maxZ] (range: ${maxZ - minZ})")
            val scaledMinX = minX * CoordinateUtil.DEFAULT_SCALE
            val scaledMaxX = maxX * CoordinateUtil.DEFAULT_SCALE
            val scaledMinY = -maxY * CoordinateUtil.DEFAULT_SCALE  // Y inverted
            val scaledMaxY = -minY * CoordinateUtil.DEFAULT_SCALE
            val scaledMinZ = -maxZ * CoordinateUtil.DEFAULT_SCALE  // Z inverted
            val scaledMaxZ = -minZ * CoordinateUtil.DEFAULT_SCALE
            println("=== SCALED (OpenGL) vertex bounds (scale=1/128) ===")
            println("  X: [$scaledMinX, $scaledMaxX] (range: ${scaledMaxX - scaledMinX})")
            println("  Y: [$scaledMinY, $scaledMaxY] (range: ${scaledMaxY - scaledMinY})")
            println("  Z: [$scaledMinZ, $scaledMaxZ] (range: ${scaledMaxZ - scaledMinZ})")
        }

        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        // Calculate per-vertex normals by accumulating face normals (smooth shading)
        val vertexNormals = Array(def.vertexCount) { VertexNormal() }

        // First pass: accumulate face normals at each vertex
        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            val ax = def.vertexX[aIdx]
            val ay = def.vertexY[aIdx]
            val az = def.vertexZ[aIdx]
            val bx = def.vertexX[bIdx]
            val by = def.vertexY[bIdx]
            val bz = def.vertexZ[bIdx]
            val cx = def.vertexX[cIdx]
            val cy = def.vertexY[cIdx]
            val cz = def.vertexZ[cIdx]

            val dx1 = bx - ax
            val dy1 = by - ay
            val dz1 = bz - az
            val dx2 = cx - ax
            val dy2 = cy - ay
            val dz2 = cz - az

            var nx = (dy1 * dz2 - dz1 * dy2).toFloat()
            var ny = (dz1 * dx2 - dx1 * dz2).toFloat()
            var nz = (dx1 * dy2 - dy1 * dx2).toFloat()

            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                len = 1f / len
                nx *= len
                ny *= len
                nz *= len
            }

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

        fun getVertexNormal(idx: Int): Vector3f {
            val vn = vertexNormals[idx]
            if (vn.magnitude == 0) return Vector3f(0f, 1f, 0f)

            var nx = vn.x / vn.magnitude
            var ny = vn.y / vn.magnitude
            var nz = vn.z / vn.magnitude

            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) {
                val invLen = 1f / len
                nx *= invLen
                ny *= invLen
                nz *= invLen
            }

            return Vector3f(nx, -ny, -nz)
        }

        // Group faces by texture ID (-1 for untextured)
        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()

        for (i in 0 until def.faceCount) {
            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            // Check alpha - RS uses inverted alpha where:
            // - alpha = 0 means OPAQUE (fully visible)
            // - alpha = 255 means FULLY TRANSPARENT (invisible)
            // If faceAlphas is null, faces are opaque (visible)
            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue  // Skip fully transparent faces

            val textureId = def.faceTextures?.get(i)?.toInt() ?: -1

            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            val hsl = def.faceColors[i].toInt() and 0xffff
            // HSL value 0 is valid (black), only 65535 (0xFFFF) is a sentinel for "no color"
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f) // White for textured faces without explicit color
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            val norm0 = getVertexNormal(aIdx)
            val norm1 = getVertexNormal(bIdx)
            val norm2 = getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5])
            )
        }

        if (facesByTexture.isEmpty()) return null

        val result = mutableListOf<MeshData>()

        // Debug: log batch info for combined models (debugModelId != -1 means debug is enabled)
        if (debugModelId != -1) {
            println("=== createMeshDataListFromDef for model $debugModelId ===")
            println("  Total faces processed: ${facesByTexture.values.sumOf { it.size }}")
            println("  Batches: ${facesByTexture.size}")
            for ((texId, faces) in facesByTexture) {
                println("    textureId=$texId: ${faces.size} faces")
            }
        }

        // Build MeshData for each texture batch (including -1 for untextured)
        for ((textureId, faces) in facesByTexture) {
            val meshData = buildMeshData(faces, textureId)
            result.add(meshData)
        }

        return result
    }

    private fun buildMeshData(faces: List<FaceData>, textureId: Int): MeshData {
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

            normals.addAll(listOf(face.n0.x, face.n0.y, face.n0.z))
            normals.addAll(listOf(face.n1.x, face.n1.y, face.n1.z))
            normals.addAll(listOf(face.n2.x, face.n2.y, face.n2.z))

            indices.add(vertexIndex)
            indices.add(vertexIndex + 1)
            indices.add(vertexIndex + 2)
            vertexIndex += 3
        }

        return MeshData(
            positions = positions.toFloatArray(),
            colors = if (colors.isNotEmpty()) colors.toFloatArray() else null,
            textureCoords = if (texCoords.isNotEmpty()) texCoords.toFloatArray() else null,
            normals = if (normals.isNotEmpty()) normals.toFloatArray() else null,
            indices = indices.toIntArray(),
            textureId = textureId
        )
    }

    fun clearCache() {
        definitionCache.clear()
        textureBoundsCache.clear()
        combinedDefinitionCache.clear()
    }

    fun getCachedDefinition(modelId: Int): ModelDefinition? = definitionCache[modelId]

    // Cache for combined model definitions (key is sorted list of model IDs)
    private val combinedDefinitionCache = mutableMapOf<List<Int>, ModelDefinition?>()

    /**
     * Load multiple model definitions and combine them into a single definition.
     * This matches how the RS client combines multiple models for multi-model objects
     * (e.g., fountains with water + stone base, lanterns with frame + window).
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @return Combined model definition, or null if no models could be loaded
     */
    fun loadCombinedDefinition(cache: Cache, modelIds: List<Int>): ModelDefinition? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return loadDefinition(cache, modelIds[0])

        // Use sorted list as cache key for consistent lookup
        val cacheKey = modelIds.sorted()
        return combinedDefinitionCache.getOrPut(cacheKey) {
            // Load all individual models
            val models = modelIds.mapNotNull { loadDefinition(cache, it) }
            if (models.isEmpty()) return@getOrPut null

            // Combine using the first model's ID as the combined ID
            // (with negative sign to distinguish from regular model IDs)
            val combinedId = -(modelIds.hashCode() and 0x7FFFFFFF)
            ModelDefinition.combine(models, combinedId)
        }
    }

    /**
     * Create MeshData list from multiple model IDs (combined).
     * This is the primary method for loading multi-model objects.
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @param debug Enable debug output
     * @return List of MeshData for instanced rendering, or null if loading fails
     */
    fun createCombinedMeshDataList(cache: Cache, modelIds: List<Int>, debug: Boolean = false): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataList(cache, modelIds[0], debug)

        // Debug: trace individual models before combination
        if (debug) {
            println("=== COMBINING MODELS: $modelIds ===")
            for (modelId in modelIds) {
                val def = loadDefinition(cache, modelId)
                if (def != null) {
                    println("  Model $modelId: ${def.faceCount} faces, hasTextures=${def.faceTextures != null}")
                    if (def.faceTextures != null) {
                        val texturedFaces = def.faceTextures!!.count { it.toInt() >= 0 }
                        val untexturedFaces = def.faceCount - texturedFaces
                        println("    Textured faces: $texturedFaces, Untextured faces: $untexturedFaces")
                    }
                }
            }
        }

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null

        if (debug) {
            println("  Combined: ${combinedDef.faceCount} faces, ${combinedDef.vertexCount} vertices")
            println("    hasTextures=${combinedDef.faceTextures != null}")
            if (combinedDef.faceTextures != null) {
                val texturedFaces = combinedDef.faceTextures!!.count { it.toInt() >= 0 }
                val untexturedFaces = combinedDef.faceCount - texturedFaces
                println("    Textured faces: $texturedFaces, Untextured faces: $untexturedFaces")
                // Check for invalid face indices
                var invalidFaces = 0
                for (i in 0 until combinedDef.faceCount) {
                    val a = combinedDef.faceA[i]
                    val b = combinedDef.faceB[i]
                    val c = combinedDef.faceC[i]
                    if (a < 0 || a >= combinedDef.vertexCount ||
                        b < 0 || b >= combinedDef.vertexCount ||
                        c < 0 || c >= combinedDef.vertexCount) {
                        invalidFaces++
                    }
                }
                if (invalidFaces > 0) {
                    println("    WARNING: $invalidFaces faces have invalid vertex indices!")
                }
            }
            // Check for faces skipped due to alpha=255 (fully transparent in RS inverted alpha)
            if (combinedDef.faceAlphas != null) {
                val transparentFaces = combinedDef.faceAlphas!!.count { (it.toInt() and 0xff) == 255 }
                if (transparentFaces > 0) {
                    println("    Note: $transparentFaces faces have alpha=255 (fully transparent, will be skipped)")
                }
            }
        }

        return createMeshDataListFromDef(combinedDef, if (debug) combinedDef.id else -1)
    }

    /**
     * Generate a unique combined model ID from a list of model IDs.
     * Used as a key for registering combined models with InstanceManager.
     */
    fun getCombinedModelId(modelIds: List<Int>): Int {
        if (modelIds.size == 1) return modelIds[0]
        // Use negative hash to distinguish from regular model IDs
        return -(modelIds.sorted().hashCode() and 0x7FFFFFFF)
    }
}
