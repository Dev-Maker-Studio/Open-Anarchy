package org.darkan.tools.cache.model

import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
 * Model modifications from ObjectDefinition for retexturing/recoloring.
 * These are applied when creating mesh data to substitute textures and colors.
 *
 * @param retextureSrc Source texture IDs to replace
 * @param retextureDst Destination texture IDs (same length as retextureSrc)
 * @param recolorSrc Source HSL colors to replace
 * @param recolorDst Destination HSL colors (same length as recolorSrc)
 */
data class ModelModifications(
    val retextureSrc: ShortArray? = null,
    val retextureDst: ShortArray? = null,
    val recolorSrc: ShortArray? = null,
    val recolorDst: ShortArray? = null
) {
    /**
     * Check if any modifications are defined.
     */
    fun hasModifications(): Boolean {
        return (retextureSrc != null && retextureSrc.isNotEmpty()) ||
               (recolorSrc != null && recolorSrc.isNotEmpty())
    }

    /**
     * Apply texture substitution to a texture ID.
     */
    fun applyRetexture(textureId: Int): Int {
        if (retextureSrc == null || retextureDst == null) return textureId
        for (i in retextureSrc.indices) {
            val srcTexId = retextureSrc[i].toInt() and 0xffff
            if (srcTexId == textureId && i < retextureDst.size) {
                val dstTexId = retextureDst[i].toInt() and 0xffff
                return dstTexId
            }
        }
        return textureId
    }

    /**
     * Apply color substitution to an HSL color.
     */
    fun applyRecolor(hsl: Int): Int {
        if (recolorSrc == null || recolorDst == null) return hsl
        for (i in recolorSrc.indices) {
            val srcHsl = recolorSrc[i].toInt() and 0xffff
            if (srcHsl == hsl && i < recolorDst.size) {
                val dstHsl = recolorDst[i].toInt() and 0xffff
                return dstHsl
            }
        }
        return hsl
    }

    /**
     * Generate a unique key component for caching based on modifications.
     */
    fun cacheKeyComponent(): String {
        if (!hasModifications()) return ""
        val sb = StringBuilder()
        if (retextureSrc != null && retextureDst != null) {
            sb.append("_rt")
            for (i in retextureSrc.indices) {
                if (i < retextureDst.size) {
                    sb.append("_${retextureSrc[i].toInt() and 0xffff}>${retextureDst[i].toInt() and 0xffff}")
                }
            }
        }
        if (recolorSrc != null && recolorDst != null) {
            sb.append("_rc")
            for (i in recolorSrc.indices) {
                if (i < recolorDst.size) {
                    sb.append("_${recolorSrc[i].toInt() and 0xffff}>${recolorDst[i].toInt() and 0xffff}")
                }
            }
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelModifications
        if (retextureSrc != null) {
            if (other.retextureSrc == null) return false
            if (!retextureSrc.contentEquals(other.retextureSrc)) return false
        } else if (other.retextureSrc != null) return false
        if (retextureDst != null) {
            if (other.retextureDst == null) return false
            if (!retextureDst.contentEquals(other.retextureDst)) return false
        } else if (other.retextureDst != null) return false
        if (recolorSrc != null) {
            if (other.recolorSrc == null) return false
            if (!recolorSrc.contentEquals(other.recolorSrc)) return false
        } else if (other.recolorSrc != null) return false
        if (recolorDst != null) {
            if (other.recolorDst == null) return false
            if (!recolorDst.contentEquals(other.recolorDst)) return false
        } else if (other.recolorDst != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = retextureSrc?.contentHashCode() ?: 0
        result = 31 * result + (retextureDst?.contentHashCode() ?: 0)
        result = 31 * result + (recolorSrc?.contentHashCode() ?: 0)
        result = 31 * result + (recolorDst?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        val NONE = ModelModifications()
    }
}

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

    /** Cache of texture transform data per model */
    private val textureTransformCache = mutableMapOf<Int, TextureTransformData?>()

    /**
     * Pre-computed texture transformation data for complex mappings (types 1-3).
     * This matches the RS client's generateTextureMatrix approach.
     */
    private data class TextureTransformData(
        val centerX: IntArray,      // Center X for each texture triangle
        val centerY: IntArray,      // Center Y for each texture triangle
        val centerZ: IntArray,      // Center Z for each texture triangle
        val matrix: Array<FloatArray>,  // 3x3 transformation matrix for each texture triangle
        val complexIndex: IntArray,  // Pre-computed index into complex texture arrays for each texture pointer
        val cubeIndex: IntArray      // Pre-computed index into cube texture arrays for each texture pointer
    )

    /**
     * Generate texture transformation data for all texture triangles in a model.
     * This computes bounding boxes and transformation matrices for complex mappings.
     * Also pre-computes indices into complex/cube texture arrays for O(1) lookup.
     */
    private fun generateTextureTransformData(def: ModelDefinition): TextureTransformData? {
        if (def.textureTriangleCount == 0 || def.texturesMapping == null) return null

        val numTextures = def.textureTriangleCount

        // Pre-compute indices into complex and cube texture arrays (O(n) once instead of O(n) per face)
        val complexIndex = IntArray(numTextures)
        val cubeIndex = IntArray(numTextures)
        var complexCount = 0
        var cubeCount = 0
        for (i in 0 until numTextures) {
            complexIndex[i] = complexCount
            cubeIndex[i] = cubeCount
            val mapping = def.texturesMapping[i].toInt() and 0xff
            if (mapping in 1..3) complexCount++
            if (mapping == 2) cubeCount++
        }

        // Initialize min/max arrays for computing centers
        val minX = IntArray(numTextures) { Int.MAX_VALUE }
        val maxX = IntArray(numTextures) { Int.MIN_VALUE }
        val minY = IntArray(numTextures) { Int.MAX_VALUE }
        val maxY = IntArray(numTextures) { Int.MIN_VALUE }
        val minZ = IntArray(numTextures) { Int.MAX_VALUE }
        val maxZ = IntArray(numTextures) { Int.MIN_VALUE }

        // Accumulate bounds for each texture from faces that use it
        for (faceIdx in 0 until def.faceCount) {
            val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255
            if (texturePointer == 255 || texturePointer >= numTextures) continue

            for (axis in 0..2) {
                val vertIdx = when (axis) {
                    0 -> def.faceA[faceIdx]
                    1 -> def.faceB[faceIdx]
                    else -> def.faceC[faceIdx]
                }
                if (vertIdx < 0 || vertIdx >= def.vertexCount) continue

                val x = def.vertexX[vertIdx]
                val y = def.vertexY[vertIdx]
                val z = def.vertexZ[vertIdx]

                if (x < minX[texturePointer]) minX[texturePointer] = x
                if (x > maxX[texturePointer]) maxX[texturePointer] = x
                if (y < minY[texturePointer]) minY[texturePointer] = y
                if (y > maxY[texturePointer]) maxY[texturePointer] = y
                if (z < minZ[texturePointer]) minZ[texturePointer] = z
                if (z > maxZ[texturePointer]) maxZ[texturePointer] = z
            }
        }

        // Compute centers and transformation matrices
        val centerX = IntArray(numTextures)
        val centerY = IntArray(numTextures)
        val centerZ = IntArray(numTextures)
        val matrices = Array(numTextures) { floatArrayOf() }

        for (texIdx in 0 until numTextures) {
            val mapping = def.texturesMapping[texIdx].toInt() and 0xff
            if (mapping > 0) {
                // Compute center of bounding box
                centerX[texIdx] = (minX[texIdx] + maxX[texIdx]) / 2
                centerY[texIdx] = (minY[texIdx] + maxY[texIdx]) / 2
                centerZ[texIdx] = (minZ[texIdx] + maxZ[texIdx]) / 2

                // Use pre-computed complex index for array access
                val complexIdx = complexIndex[texIdx]

                // Get scale factors based on mapping type
                val scaleX: Float
                val scaleY: Float
                val scaleZ: Float

                if (mapping == 1) {
                    // Cylindrical mapping
                    val x = def.texturesScaleX?.get(complexIdx) ?: 0
                    if (x == 0) {
                        scaleX = 1.0f
                        scaleZ = 1.0f
                    } else if (x > 0) {
                        scaleX = 1.0f
                        scaleZ = x / 1024.0f
                    } else {
                        scaleZ = 1.0f
                        scaleX = (-x) / 1024.0f
                    }
                    scaleY = 64.0f / (def.texturesScaleY?.get(complexIdx) ?: 1)
                } else if (mapping == 2) {
                    // Cube mapping - protect against division by zero
                    val rawX = def.texturesScaleX?.get(complexIdx) ?: 1
                    val rawY = def.texturesScaleY?.get(complexIdx) ?: 1
                    val rawZ = def.texturesScaleZ?.get(complexIdx) ?: 1
                    scaleX = 64.0f / (if (rawX == 0) 1 else rawX)
                    scaleY = 64.0f / (if (rawY == 0) 1 else rawY)
                    scaleZ = 64.0f / (if (rawZ == 0) 1 else rawZ)
                } else {
                    // Spherical mapping (type 3)
                    scaleX = (def.texturesScaleX?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleY = (def.texturesScaleY?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleZ = (def.texturesScaleZ?.get(complexIdx) ?: 1024) / 1024.0f
                }

                // Get rotation and texture triangle vertices
                val rotation = def.texturesRotation?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
                val n = def.textureFaceX?.get(texIdx)?.toInt() ?: 0
                val m = def.textureFaceY?.get(texIdx)?.toInt() ?: 0
                val p = def.textureFaceZ?.get(texIdx)?.toInt() ?: 0

                matrices[texIdx] = createTextureMatrix(n, m, p, rotation, scaleX, scaleY, scaleZ)
            }
        }

        return TextureTransformData(centerX, centerY, centerZ, matrices, complexIndex, cubeIndex)
    }

    /**
     * Create a 3x3 texture transformation matrix.
     * Combines Y-axis rotation with axis-angle rotation derived from texture triangle vertices.
     */
    private fun createTextureMatrix(n: Int, m: Int, p: Int, rotation: Int, scaleX: Float, scaleY: Float, scaleZ: Float): FloatArray {
        val rotationMap = FloatArray(9)
        val result = FloatArray(9)

        // Y-axis rotation from rotation parameter (0-255 maps to 0-2pi)
        var cosR = cos(rotation * 0.024543693f)  // rotation * (2*PI / 256)
        var sinR = sin(rotation * 0.024543693f)

        rotationMap[0] = cosR
        rotationMap[1] = 0.0f
        rotationMap[2] = sinR
        rotationMap[3] = 0.0f
        rotationMap[4] = 1.0f
        rotationMap[5] = 0.0f
        rotationMap[6] = -sinR
        rotationMap[7] = 0.0f
        rotationMap[8] = cosR

        val matrix3x3 = FloatArray(9)
        var x = 1.0f
        var y = 0.0f

        // Axis-angle rotation from texture triangle vertices
        val cosA = m / 32767.0f
        val sinA = -sqrt(1.0f - cosA * cosA)
        val omc = 1.0f - cosA

        val size = sqrt((p * p + n * n).toFloat())
        if (size == 0.0f && cosA == 0.0f) {
            return rotationMap
        }

        if (size != 0.0f) {
            x = (-p) / size
            y = n.toFloat() / size
        }

        matrix3x3[0] = cosA + x * x * omc
        matrix3x3[1] = y * sinA
        matrix3x3[2] = y * x * omc
        matrix3x3[3] = -y * sinA
        matrix3x3[4] = cosA
        matrix3x3[5] = x * sinA
        matrix3x3[6] = x * y * omc
        matrix3x3[7] = -x * sinA
        matrix3x3[8] = cosA + y * y * omc

        // Multiply rotation matrices
        result[0] = rotationMap[0] * matrix3x3[0] + rotationMap[1] * matrix3x3[3] + rotationMap[2] * matrix3x3[6]
        result[1] = rotationMap[0] * matrix3x3[1] + rotationMap[1] * matrix3x3[4] + rotationMap[2] * matrix3x3[7]
        result[2] = rotationMap[0] * matrix3x3[2] + rotationMap[1] * matrix3x3[5] + rotationMap[2] * matrix3x3[8]
        result[3] = rotationMap[3] * matrix3x3[0] + rotationMap[4] * matrix3x3[3] + rotationMap[5] * matrix3x3[6]
        result[4] = rotationMap[3] * matrix3x3[1] + rotationMap[4] * matrix3x3[4] + rotationMap[5] * matrix3x3[7]
        result[5] = rotationMap[3] * matrix3x3[2] + rotationMap[4] * matrix3x3[5] + rotationMap[5] * matrix3x3[8]
        result[6] = rotationMap[6] * matrix3x3[0] + rotationMap[7] * matrix3x3[3] + rotationMap[8] * matrix3x3[6]
        result[7] = rotationMap[6] * matrix3x3[1] + rotationMap[7] * matrix3x3[4] + rotationMap[8] * matrix3x3[7]
        result[8] = rotationMap[6] * matrix3x3[2] + rotationMap[7] * matrix3x3[5] + rotationMap[8] * matrix3x3[8]

        // Apply scale
        result[0] *= scaleX
        result[1] *= scaleX
        result[2] *= scaleX
        result[3] *= scaleY
        result[4] *= scaleY
        result[5] *= scaleY
        result[6] *= scaleZ
        result[7] *= scaleZ
        result[8] *= scaleZ

        return result
    }

    /**
     * Map cylindrical UV coordinates (type 1).
     */
    private fun mapCylindricalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, offset: Float, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Transform by matrix
        val y = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val height = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val x = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        // Cylindrical projection
        var u = atan2(y, x) / (2 * Math.PI.toFloat()) + 0.5f
        if (offset != 1.0f) {
            u *= offset
        }
        var v = height + 0.5f + speedOffset

        // Apply direction rotation
        applyDirection(direction, u, v, uv)
    }

    /**
     * Map spherical UV coordinates (type 3).
     */
    private fun mapSphericalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Transform by matrix
        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        val dist = sqrt(tx * tx + ty * ty + tz * tz)

        // Spherical projection
        var u = atan2(tx, tz) / (2 * Math.PI.toFloat()) + 0.5f
        var v = asin(ty / dist) / Math.PI.toFloat() + 0.5f + speedOffset

        // Apply direction rotation
        applyDirection(direction, u, v, uv)
    }

    /**
     * Map cube/rectangular UV coordinates (type 2).
     *
     * This matches the RS client's mapRectangularUv function exactly.
     * The cube face determines which pair of transformed coordinates to use:
     * - Face 0 (+Y): tx for U, -tz for V
     * - Face 1 (-Y): tx for U, tz for V
     * - Face 2 (+Z): -tx for U, -ty for V
     * - Face 3 (-Z): tx for U, -ty for V
     * - Face 4 (+X): tz for U, -ty for V
     * - Face 5 (-X): -tz for U, -ty for V
     *
     * Parameters speedOffset, uTrans, vTrans come from the texture definition
     * and control animation/translation of the texture.
     */
    private fun mapCubeUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        cubeFace: Int, matrix: FloatArray, direction: Int,
        speedOffset: Float, uTrans: Float, vTrans: Float,
        uv: FloatArray
    ) {
        // Transform vertex position relative to center through texture matrix
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        // Matrix rows [0-2], [3-5], [6-8] transform to X, Y, Z axes respectively
        // This includes the scale factor that was baked into the matrix
        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        var u: Float
        var v: Float

        // Project onto appropriate cube face
        // This matches the RS client's mapRectangularUv switch statement exactly:
        // - f_14 = tx, f_15 = ty, f_16 = tz
        // - f_10 = speedOffset, f_11 = uTrans, f_12 = vTrans
        when (cubeFace) {
            0 -> { // +Y face: u = tx + speedOffset, v = -tz + vTrans
                u = tx + speedOffset + 0.5f
                v = -tz + vTrans + 0.5f
            }
            1 -> { // -Y face: u = tx + speedOffset, v = tz + vTrans
                u = tx + speedOffset + 0.5f
                v = tz + vTrans + 0.5f
            }
            2 -> { // +Z face: u = -tx + speedOffset, v = -ty + uTrans
                u = -tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            3 -> { // -Z face: u = tx + speedOffset, v = -ty + uTrans
                u = tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            4 -> { // +X face: u = tz + vTrans, v = -ty + uTrans
                u = tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
            else -> { // -X face (5): u = -tz + vTrans, v = -ty + uTrans
                u = -tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
        }

        // Apply direction rotation (0-3 for 0/90/180/270 degrees)
        applyDirection(direction, u, v, uv)
    }

    /**
     * Determine which face of a cube a transformed normal points to.
     *
     * This matches the RS client's getCubeFace function exactly.
     * Returns:
     * - 0: +Y face (normal points up)
     * - 1: -Y face (normal points down)
     * - 2: +Z face (normal points forward)
     * - 3: -Z face (normal points backward)
     * - 4: +X face (normal points right)
     * - 5: -X face (normal points left)
     *
     * The face with the largest absolute component wins.
     * Ties are broken in order: Y > Z > X (due to the if-else chain structure).
     */
    private fun getCubeFace(nx: Float, ny: Float, nz: Float): Int {
        // Get absolute values for comparison
        val ax = if (nx < 0.0f) -nx else nx
        val ay = if (ny < 0.0f) -ny else ny
        val az = if (nz < 0.0f) -nz else nz

        // Determine dominant axis and sign
        // This matches the client's ternary operator chain exactly
        return if (ay > ax && ay > az) {
            if (ny > 0.0f) 0 else 1  // Y is dominant: +Y or -Y
        } else if (az > ax && az > ay) {
            if (nz > 0.0f) 2 else 3  // Z is dominant: +Z or -Z
        } else {
            if (nx > 0.0f) 4 else 5  // X is dominant (or tied): +X or -X
        }
    }

    /**
     * Apply direction rotation to UV coordinates and flip V for OpenGL.
     * Direction 0 = no rotation, 1-3 = 90/180/270 degree rotations.
     *
     * The V flip (1 - V) corrects for the difference between RS and OpenGL texture coordinates:
     * - RS textures have V=0 at top, V=1 at bottom
     * - OpenGL textures have V=0 at bottom, V=1 at top
     * - Since we flip texture data when loading (TextureLoader.createTextureFromArgb),
     *   we must also flip V coordinates for correct sampling.
     * - This works correctly for repeating textures because:
     *   frac(1 - V) maps RS sample positions to correct OpenGL positions.
     */
    private fun applyDirection(direction: Int, u: Float, v: Float, uv: FloatArray) {
        when (direction) {
            1 -> {
                uv[0] = -v
                uv[1] = u
            }
            2 -> {
                uv[0] = -u
                uv[1] = -v
            }
            3 -> {
                uv[0] = v
                uv[1] = -u
            }
            else -> {
                uv[0] = u
                uv[1] = v
            }
        }
        // Flip V for OpenGL (RS has V=0 at top, OpenGL has V=0 at bottom with flipped texture)
        uv[1] = 1.0f - uv[1]
    }

    /**
     * Compute UV coordinates for a textured face using RS texture triangle projection.
     *
     * Supports all 4 mapping types:
     * - Type 0: Planar projection onto texture triangle plane
     * - Type 1: Cylindrical projection
     * - Type 2: Cube/rectangular projection
     * - Type 3: Spherical projection
     *
     * If faceTexturePointer == -1: use bounding-box normalized UVs (consistent across all faces)
     */
    private fun computeUVs(def: ModelDefinition, faceIdx: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int = -1): FloatArray {
        val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255

        // If no texture pointer or invalid, use bounding-box normalized UVs
        if (texturePointer == 255 || texturePointer >= def.textureTriangleCount) {
            return computeFallbackUVs(def, aIdx, bIdx, cIdx, textureId)
        }

        val mappingType = def.texturesMapping?.get(texturePointer)?.toInt()?.and(0xff) ?: 0

        return when (mappingType) {
            0 -> computePlanarUVs(def, texturePointer, aIdx, bIdx, cIdx, textureId)
            1 -> computeCylindricalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            2 -> computeCubeUVs(def, faceIdx, texturePointer, aIdx, bIdx, cIdx)
            3 -> computeSphericalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            else -> computeFallbackUVs(def, aIdx, bIdx, cIdx, textureId)
        }
    }

    /**
     * Fallback UV calculation using bounding-box normalization.
     */
    private fun computeFallbackUVs(def: ModelDefinition, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int): FloatArray {
        val cacheKey = Pair(def.id, textureId)
        val bounds = textureBoundsCache.getOrPut(cacheKey) {
            computeTextureBounds(def, textureId)
        }

        val aU = (def.vertexX[aIdx] - bounds.minX) / bounds.rangeX
        val aV = 1.0f - (def.vertexY[aIdx] - bounds.minY) / bounds.rangeY  // Flip V for OpenGL
        val bU = (def.vertexX[bIdx] - bounds.minX) / bounds.rangeX
        val bV = 1.0f - (def.vertexY[bIdx] - bounds.minY) / bounds.rangeY  // Flip V for OpenGL
        val cU = (def.vertexX[cIdx] - bounds.minX) / bounds.rangeX
        val cV = 1.0f - (def.vertexY[cIdx] - bounds.minY) / bounds.rangeY  // Flip V for OpenGL
        return floatArrayOf(aU, aV, bU, bV, cU, cV)
    }

    /**
     * Type 0: Planar projection onto texture triangle plane.
     */
    private fun computePlanarUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int): FloatArray {
        val texFaceX = def.textureFaceX ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        val texFaceY = def.textureFaceY ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)
        val texFaceZ = def.textureFaceZ ?: return floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f)

        val tAIdx = texFaceX[texturePointer].toInt() and 0xffff
        val tBIdx = texFaceY[texturePointer].toInt() and 0xffff
        val tCIdx = texFaceZ[texturePointer].toInt() and 0xffff

        if (tAIdx >= def.vertexCount || tBIdx >= def.vertexCount || tCIdx >= def.vertexCount) {
            return computeFallbackUVs(def, aIdx, bIdx, cIdx, textureId)
        }

        val tAx = def.vertexX[tAIdx].toFloat()
        val tAy = def.vertexY[tAIdx].toFloat()
        val tAz = def.vertexZ[tAIdx].toFloat()

        val e1x = def.vertexX[tBIdx].toFloat() - tAx
        val e1y = def.vertexY[tBIdx].toFloat() - tAy
        val e1z = def.vertexZ[tBIdx].toFloat() - tAz

        val e2x = def.vertexX[tCIdx].toFloat() - tAx
        val e2y = def.vertexY[tCIdx].toFloat() - tAy
        val e2z = def.vertexZ[tCIdx].toFloat() - tAz

        val v0x = def.vertexX[aIdx].toFloat() - tAx
        val v0y = def.vertexY[aIdx].toFloat() - tAy
        val v0z = def.vertexZ[aIdx].toFloat() - tAz

        val v1x = def.vertexX[bIdx].toFloat() - tAx
        val v1y = def.vertexY[bIdx].toFloat() - tAy
        val v1z = def.vertexZ[bIdx].toFloat() - tAz

        val v2x = def.vertexX[cIdx].toFloat() - tAx
        val v2y = def.vertexY[cIdx].toFloat() - tAy
        val v2z = def.vertexZ[cIdx].toFloat() - tAz

        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x

        val uBasisX = e2y * nz - e2z * ny
        val uBasisY = e2z * nx - e2x * nz
        val uBasisZ = e2x * ny - e2y * nx
        var uDenom = uBasisX * e1x + uBasisY * e1y + uBasisZ * e1z
        if (uDenom == 0f) uDenom = 1f
        val uScale = 1f / uDenom

        val vBasisX = e1y * nz - e1z * ny
        val vBasisY = e1z * nx - e1x * nz
        val vBasisZ = e1x * ny - e1y * nx
        var vDenom = vBasisX * e2x + vBasisY * e2y + vBasisZ * e2z
        if (vDenom == 0f) vDenom = 1f
        val vScale = 1f / vDenom

        val aU = (uBasisX * v0x + uBasisY * v0y + uBasisZ * v0z) * uScale
        val aV = 1.0f - (vBasisX * v0x + vBasisY * v0y + vBasisZ * v0z) * vScale  // Flip V for OpenGL
        val bU = (uBasisX * v1x + uBasisY * v1y + uBasisZ * v1z) * uScale
        val bV = 1.0f - (vBasisX * v1x + vBasisY * v1y + vBasisZ * v1z) * vScale  // Flip V for OpenGL
        val cU = (uBasisX * v2x + uBasisY * v2y + uBasisZ * v2z) * uScale
        val cV = 1.0f - (vBasisX * v2x + vBasisY * v2y + vBasisZ * v2z) * vScale  // Flip V for OpenGL

        return floatArrayOf(aU, aV, bU, bV, cU, cV)
    }

    /**
     * Type 1: Cylindrical projection.
     */
    private fun computeCylindricalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        // Get or compute texture transform data
        val transformData = textureTransformCache.getOrPut(def.id) {
            generateTextureTransformData(def)
        } ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed complex texture index (O(1) instead of O(n))
        val complexIdx = transformData.complexIndex[texturePointer]
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f
        val offset = (def.texturesScaleZ?.get(complexIdx) ?: 1024) / 1024.0f

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapCylindricalUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvA)
        mapCylindricalUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvB)
        mapCylindricalUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, matrix, offset, direction, speedOffset, uvC)

        // Apply UV seam correction for cylindrical mapping
        val halfOffset = offset / 2.0f
        if ((direction and 1) == 0) {
            // Correct U seam
            if (uvB[0] - uvA[0] > halfOffset) uvB[0] -= offset
            else if (uvA[0] - uvB[0] > halfOffset) uvB[0] += offset
            if (uvC[0] - uvA[0] > halfOffset) uvC[0] -= offset
            else if (uvA[0] - uvC[0] > halfOffset) uvC[0] += offset
        } else {
            // Correct V seam
            if (uvB[1] - uvA[1] > halfOffset) uvB[1] -= offset
            else if (uvA[1] - uvB[1] > halfOffset) uvB[1] += offset
            if (uvC[1] - uvA[1] > halfOffset) uvC[1] -= offset
            else if (uvA[1] - uvC[1] > halfOffset) uvC[1] += offset
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    /**
     * Type 2: Cube/rectangular projection.
     *
     * This matches the RS client's cube mapping logic exactly (ModelGL.kt lines 715-740):
     * 1. Compute face normal via cross product of edges
     * 2. Transform normal by texture matrix (which has scale baked in)
     * 3. UN-scale the transformed normal to determine which cube face
     * 4. Project all three vertices using mapCubeUV (which uses the scaled matrix)
     *
     * The key insight is that scale is baked into the matrix for UV projection,
     * but we need to remove it for cube face determination so the face selection
     * is based on the un-scaled orientation.
     */
    // Debug flag for UV computation
    private var debugCubeUv = false

    /**
     * Enable/disable debug output for cube UV computation.
     */
    fun setDebugCubeUv(enable: Boolean) {
        debugCubeUv = enable
    }

    private fun computeCubeUVs(def: ModelDefinition, faceIdx: Int, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformData = textureTransformCache.getOrPut(def.id) {
            generateTextureTransformData(def)
        } ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed sparse indices for O(1) array access
        val complexIdx = transformData.complexIndex[texturePointer]
        val cubeIdx = transformData.cubeIndex[texturePointer]

        // Get cube mapping parameters from sparse arrays
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f
        val uTrans = (def.texturesUtrans?.get(cubeIdx) ?: 0) / 256.0f
        val vTrans = (def.texturesVtrans?.get(cubeIdx) ?: 0) / 256.0f

        // Compute face normal via cross product (B-A) x (C-A)
        // This matches the client's: i_100-i_108 calculations
        val dx1 = def.vertexX[bIdx] - def.vertexX[aIdx]
        val dy1 = def.vertexY[bIdx] - def.vertexY[aIdx]
        val dz1 = def.vertexZ[bIdx] - def.vertexZ[aIdx]
        val dx2 = def.vertexX[cIdx] - def.vertexX[aIdx]
        val dy2 = def.vertexY[cIdx] - def.vertexY[aIdx]
        val dz2 = def.vertexZ[cIdx] - def.vertexZ[aIdx]
        val fnx = dy1 * dz2 - dz1 * dy2  // i_106
        val fny = dz1 * dx2 - dx1 * dz2  // i_107
        val fnz = dx1 * dy2 - dy1 * dx2  // i_108

        // Get scales for un-scaling the transformed normal
        // The matrix already has scale baked in, so we need to divide by scale
        // to get the true face orientation. This matches: f_52, f_53, f_54
        val rawScaleX = def.texturesScaleX?.get(complexIdx) ?: 1
        val rawScaleY = def.texturesScaleY?.get(complexIdx) ?: 1
        val rawScaleZ = def.texturesScaleZ?.get(complexIdx) ?: 1
        // Protect against division by zero
        val scaleX = 64.0f / (if (rawScaleX == 0) 1 else rawScaleX)
        val scaleY = 64.0f / (if (rawScaleY == 0) 1 else rawScaleY)
        val scaleZ = 64.0f / (if (rawScaleZ == 0) 1 else rawScaleZ)

        // Transform normal by matrix, then divide by scale to un-do the scaling
        // This gives us the true face orientation for cube face selection
        // Matches client: f_55 = (i_106 * matrix[0] + i_107 * matrix[1] + i_108 * matrix[2]) / f_52
        val tnx = (fnx * matrix[0] + fny * matrix[1] + fnz * matrix[2]) / scaleX
        val tny = (fnx * matrix[3] + fny * matrix[4] + fnz * matrix[5]) / scaleY
        val tnz = (fnx * matrix[6] + fny * matrix[7] + fnz * matrix[8]) / scaleZ
        val cubeFace = getCubeFace(tnx, tny, tnz)

        if (debugCubeUv && faceIdx < 3) {
            println("  computeCubeUVs(face=$faceIdx, texPtr=$texturePointer, verts=($aIdx,$bIdx,$cIdx))")
            println("    center=($centerX, $centerY, $centerZ)")
            println("    direction=$direction, speedOffset=$speedOffset, uTrans=$uTrans, vTrans=$vTrans")
            println("    rawScale=($rawScaleX, $rawScaleY, $rawScaleZ) -> scale=($scaleX, $scaleY, $scaleZ)")
            println("    faceNormal=($fnx, $fny, $fnz)")
            println("    transformedNormal=($tnx, $tny, $tnz) -> cubeFace=$cubeFace")
            println("    matrix=[${matrix.take(3).joinToString()}], [${matrix.drop(3).take(3).joinToString()}], [${matrix.drop(6).joinToString()}]")
        }

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapCubeUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvA)
        mapCubeUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvB)
        mapCubeUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvC)

        if (debugCubeUv && faceIdx < 3) {
            println("    A(${def.vertexX[aIdx]}, ${def.vertexY[aIdx]}, ${def.vertexZ[aIdx]}) -> UV(${uvA[0]}, ${uvA[1]})")
            println("    B(${def.vertexX[bIdx]}, ${def.vertexY[bIdx]}, ${def.vertexZ[bIdx]}) -> UV(${uvB[0]}, ${uvB[1]})")
            println("    C(${def.vertexX[cIdx]}, ${def.vertexY[cIdx]}, ${def.vertexZ[cIdx]}) -> UV(${uvC[0]}, ${uvC[1]})")
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    /**
     * Type 3: Spherical projection.
     */
    private fun computeSphericalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformData = textureTransformCache.getOrPut(def.id) {
            generateTextureTransformData(def)
        } ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        // Use pre-computed complex texture index (O(1) instead of O(n))
        val complexIdx = transformData.complexIndex[texturePointer]
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapSphericalUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvA)
        mapSphericalUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvB)
        mapSphericalUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvC)

        // Apply UV seam correction for spherical mapping
        if ((direction and 1) == 0) {
            // Correct U seam
            if (uvB[0] - uvA[0] > 0.5f) uvB[0] -= 1.0f
            else if (uvA[0] - uvB[0] > 0.5f) uvB[0] += 1.0f
            if (uvC[0] - uvA[0] > 0.5f) uvC[0] -= 1.0f
            else if (uvA[0] - uvC[0] > 0.5f) uvC[0] += 1.0f
        } else {
            // Correct V seam
            if (uvB[1] - uvA[1] > 0.5f) uvB[1] -= 1.0f
            else if (uvA[1] - uvB[1] > 0.5f) uvB[1] += 1.0f
            if (uvC[1] - uvA[1] > 0.5f) uvC[1] -= 1.0f
            else if (uvA[1] - uvC[1] > 0.5f) uvC[1] += 1.0f
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
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
            // faceTextures values are stored as signed Short but represent unsigned texture IDs
            // Use signed comparison for -1 (no texture), but apply 0xffff mask for actual IDs
            val textureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
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
        return createMeshDataListFromDef(def, ModelModifications.NONE, if (debug) modelId else -1)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering with modifications.
     * Similar to createTexturedModel but returns raw arrays instead of GPU meshes.
     *
     * @param cache The game cache
     * @param modelId The model ID to load
     * @param modifications Retexture/recolor modifications to apply
     * @param debug Enable debug output
     */
    fun createMeshDataListWithModifications(
        cache: Cache,
        modelId: Int,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, modifications, if (debug) modelId else -1)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     * Overload without modifications for backwards compatibility.
     */
    fun createMeshDataListFromDef(def: ModelDefinition, debugModelId: Int = -1): List<MeshData>? {
        return createMeshDataListFromDef(def, ModelModifications.NONE, debugModelId)
    }

    /**
     * Create MeshData list from a model definition for instanced rendering.
     *
     * @param def The model definition
     * @param modifications Retexture/recolor modifications to apply
     * @param debugModelId Unused, kept for API compatibility
     */
    @Suppress("UNUSED_PARAMETER")
    fun createMeshDataListFromDef(def: ModelDefinition, modifications: ModelModifications, debugModelId: Int = -1): List<MeshData>? {
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

            // Get raw texture ID and apply retexturing if modifications provided
            // Note: faceTextures values are stored as signed Short but represent unsigned texture IDs
            // Use signed comparison for -1 (no texture), but apply 0xffff mask for actual IDs
            val rawTextureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val rawTextureId = if (rawTextureIdSigned == -1) -1 else (rawTextureIdSigned and 0xffff)
            val textureId = if (rawTextureId >= 0) {
                modifications.applyRetexture(rawTextureId)
            } else {
                rawTextureId
            }

            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            // Get raw HSL color and apply recoloring if modifications provided
            val rawHsl = def.faceColors[i].toInt() and 0xffff
            val hsl = modifications.applyRecolor(rawHsl)
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
        textureTransformCache.clear()
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
     * @param debug Enable debug output (currently unused, kept for API compatibility)
     * @return List of MeshData for instanced rendering, or null if loading fails
     */
    fun createCombinedMeshDataList(cache: Cache, modelIds: List<Int>, debug: Boolean = false): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataList(cache, modelIds[0], debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, ModelModifications.NONE, -1)
    }

    /**
     * Create MeshData list from multiple model IDs (combined) with modifications.
     * This is the primary method for loading multi-model objects with retexturing/recoloring.
     *
     * @param cache The game cache
     * @param modelIds List of model IDs to combine
     * @param modifications Retexture/recolor modifications to apply
     * @param debug Enable debug output (currently unused, kept for API compatibility)
     * @return List of MeshData for instanced rendering, or null if loading fails
     */
    fun createCombinedMeshDataListWithModifications(
        cache: Cache,
        modelIds: List<Int>,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataListWithModifications(cache, modelIds[0], modifications, debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, modifications, -1)
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

    /**
     * Generate a unique combined model ID that includes modifications.
     * Used as a key for registering combined models with retexturing/recoloring.
     *
     * @param modelIds List of model IDs
     * @param modifications Retexture/recolor modifications
     * @return A unique ID for the model+modifications combination
     */
    fun getCombinedModelIdWithModifications(modelIds: List<Int>, modifications: ModelModifications): Int {
        val baseId = getCombinedModelId(modelIds)
        if (!modifications.hasModifications()) return baseId
        // Combine base ID with modifications hash for unique key
        val modHash = modifications.hashCode() and 0x7FFFFFFF
        // Use a different sign pattern to distinguish from plain combined models
        return -(baseId xor modHash)
    }

}
