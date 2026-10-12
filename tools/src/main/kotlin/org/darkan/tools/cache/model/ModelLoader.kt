package org.darkan.tools.cache.model

import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.util.ColorUtil
import org.darkan.tools.util.CoordinateUtil
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.BillboardTypeLoader
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TextureBatch(
    val mesh: Mesh,
    val textureId: Int
)

data class TexturedModel(
    val colorBatch: Mesh?,
    val textureBatches: List<TextureBatch>,
    val hasTextures: Boolean
) {
    val mesh: Mesh? get() = colorBatch

    fun cleanup() {
        colorBatch?.cleanup()
        textureBatches.forEach { it.mesh.cleanup() }
    }
}

private data class FaceData(
    val v0: Vector3f, val v1: Vector3f, val v2: Vector3f,
    val color: Vector3f,
    val n0: Vector3f, val n1: Vector3f, val n2: Vector3f,
    val u0: Float, val v0Uv: Float,
    val u1: Float, val v1Uv: Float,
    val u2: Float, val v2Uv: Float,
    val sv0: Int = -1, val sv1: Int = -1, val sv2: Int = -1
)

data class ModelModifications(
    val retextureSrc: ShortArray? = null,
    val retextureDst: ShortArray? = null,
    val recolorSrc: ShortArray? = null,
    val recolorDst: ShortArray? = null,
    val ambient: Int = DEFAULT_AMBIENT,
    val normalMerge: NormalMerge? = null
) {
    fun hasModifications(): Boolean {
        return (retextureSrc != null && retextureSrc.isNotEmpty()) ||
               (recolorSrc != null && recolorSrc.isNotEmpty()) ||
               ambient != DEFAULT_AMBIENT ||
               normalMerge != null
    }

    fun withNormalMerge(merge: NormalMerge?): ModelModifications {
        if (merge == null) return this
        return copy(normalMerge = merge)
    }

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
        if (ambient != DEFAULT_AMBIENT) {
            sb.append("_amb$ambient")
        }
        if (normalMerge != null) {
            sb.append("_nm").append(normalMerge.signature)
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelModifications
        if (ambient != other.ambient) return false
        if (normalMerge != other.normalMerge) return false
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
        result = 31 * result + ambient
        result = 31 * result + (normalMerge?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val DEFAULT_AMBIENT = 64

        val NONE = ModelModifications()
    }
}

object ModelLoader {
    private sealed class CacheResult<out T> {
        data class Found<T>(val value: T) : CacheResult<T>()
        data object NotFound : CacheResult<Nothing>()
    }

    private val definitionCache = ConcurrentHashMap<Int, CacheResult<ModelDefinition>>()

    var hideUntexturedMarkerFaces: Boolean = true
    fun loadTexturedModel(cache: Cache, modelId: Int): TexturedModel? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createTexturedModel(def, cache = cache)
    }

    fun loadMesh(cache: Cache, modelId: Int): Mesh? {
        return loadTexturedModel(cache, modelId)?.mesh
    }

    var glbDirectory: Path? = null

    fun loadDefinition(cache: Cache, modelId: Int): ModelDefinition? {
        val result = definitionCache.computeIfAbsent(modelId) {
            val glb = glbDirectory?.resolve("$modelId.glb")?.takeIf { Files.exists(it) }
            val def = if (glb != null) ModelGltf.read(Files.readAllBytes(glb), modelId) else ModelDecoder.decode(cache, modelId)
            if (def != null) CacheResult.Found(def) else CacheResult.NotFound
        }
        return when (result) {
            is CacheResult.Found -> result.value
            is CacheResult.NotFound -> null
        }
    }

    fun modelHasTextures(cache: Cache, modelId: Int): Boolean {
        val def = loadDefinition(cache, modelId) ?: return false
        val textures = def.faceTextures ?: return false
        for (i in textures.indices) {
            if (textures[i].toInt() != -1) return true
        }
        return false
    }

    private class VertexNormal {
        var x: Float = 0f
        var y: Float = 0f
        var z: Float = 0f
        var magnitude: Int = 0
    }

    private fun accumulateVertexNormals(def: ModelDefinition): Array<VertexNormal> {
        val vertexNormals = Array(def.vertexCount) { VertexNormal() }

        for (i in 0 until def.faceCount) {
            val faceRenderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (faceRenderType != 0) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (aIdx < 0 || aIdx >= def.vertexCount) continue
            if (bIdx < 0 || bIdx >= def.vertexCount) continue
            if (cIdx < 0 || cIdx >= def.vertexCount) continue

            val ax = def.vertexX[aIdx]
            val ay = def.vertexY[aIdx]
            val az = def.vertexZ[aIdx]

            val dx1 = def.vertexX[bIdx] - ax
            val dy1 = def.vertexY[bIdx] - ay
            val dz1 = def.vertexZ[bIdx] - az
            val dx2 = def.vertexX[cIdx] - ax
            val dy2 = def.vertexY[cIdx] - ay
            val dz2 = def.vertexZ[cIdx] - az

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

        return vertexNormals
    }

    class VertexNormalField(
        val vertexX: IntArray,
        val vertexY: IntArray,
        val vertexZ: IntArray,
        val normalX: FloatArray,
        val normalY: FloatArray,
        val normalZ: FloatArray,
        val magnitude: IntArray
    ) {
        val vertexCount: Int get() = vertexX.size
    }

    private val vertexNormalFieldCache = ConcurrentHashMap<Int, CacheResult<VertexNormalField>>()

    fun vertexNormalField(cache: Cache, modelIds: List<Int>): VertexNormalField? {
        if (modelIds.isEmpty()) return null
        val combinedId = getCombinedModelId(modelIds)
        vertexNormalFieldCache[combinedId]?.let { cached ->
            return (cached as? CacheResult.Found)?.value
        }
        val result = run {
            val def = loadCombinedDefinition(cache, modelIds)
            if (def == null || def.vertexCount == 0) {
                CacheResult.NotFound
            } else {
                val accumulated = accumulateVertexNormals(def)
                val count = def.vertexCount
                val normalX = FloatArray(count)
                val normalY = FloatArray(count)
                val normalZ = FloatArray(count)
                val magnitude = IntArray(count)
                for (i in 0 until count) {
                    val vn = accumulated[i]
                    normalX[i] = vn.x
                    normalY[i] = vn.y
                    normalZ[i] = vn.z
                    magnitude[i] = vn.magnitude
                }
                CacheResult.Found(
                    VertexNormalField(
                        vertexX = def.vertexX.copyOf(count),
                        vertexY = def.vertexY.copyOf(count),
                        vertexZ = def.vertexZ.copyOf(count),
                        normalX = normalX,
                        normalY = normalY,
                        normalZ = normalZ,
                        magnitude = magnitude
                    )
                )
            }
        }
        val stored = vertexNormalFieldCache.putIfAbsent(combinedId, result) ?: result
        return when (stored) {
            is CacheResult.Found -> stored.value
            is CacheResult.NotFound -> null
        }
    }

    private fun faceNormalGL(def: ModelDefinition, aIdx: Int, bIdx: Int, cIdx: Int): Vector3f {
        val ax = def.vertexX[aIdx]
        val ay = def.vertexY[aIdx]
        val az = def.vertexZ[aIdx]
        val dx1 = def.vertexX[bIdx] - ax
        val dy1 = def.vertexY[bIdx] - ay
        val dz1 = def.vertexZ[bIdx] - az
        val dx2 = def.vertexX[cIdx] - ax
        val dy2 = def.vertexY[cIdx] - ay
        val dz2 = def.vertexZ[cIdx] - az

        var nx = (dy1 * dz2 - dz1 * dy2).toFloat()
        var ny = (dz1 * dx2 - dx1 * dz2).toFloat()
        var nz = (dx1 * dy2 - dy1 * dx2).toFloat()

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0f) {
            val invLen = 1f / len
            nx *= invLen
            ny *= invLen
            nz *= invLen
        } else {
            return Vector3f(0f, 1f, 0f)
        }

        return Vector3f(nx, -ny, -nz)
    }

    private val textureTransformCache = ConcurrentHashMap<Int, CacheResult<TextureTransformData>>()

    private data class TextureTransformData(
        val centerX: IntArray,
        val centerY: IntArray,
        val centerZ: IntArray,
        val matrix: Array<FloatArray>,
        val complexIndex: IntArray,
        val cubeIndex: IntArray
    )

    private fun generateTextureTransformData(def: ModelDefinition): TextureTransformData? {
        if (def.textureTriangleCount == 0 || def.texturesMapping == null) return null

        val numTextures = def.textureTriangleCount

        val complexIndex = IntArray(numTextures)
        val cubeIndex = IntArray(numTextures)
        var complexCount = 0
        var cubeCount = 0
        for (i in 0 until numTextures) {
            complexIndex[i] = complexCount
            cubeIndex[i] = cubeCount
            val mapping = def.texturesMapping!![i].toInt() and 0xff
            if (mapping in 1..3) complexCount++
            if (mapping == 2) cubeCount++
        }

        val minX = IntArray(numTextures) { Int.MAX_VALUE }
        val maxX = IntArray(numTextures) { Int.MIN_VALUE }
        val minY = IntArray(numTextures) { Int.MAX_VALUE }
        val maxY = IntArray(numTextures) { Int.MIN_VALUE }
        val minZ = IntArray(numTextures) { Int.MAX_VALUE }
        val maxZ = IntArray(numTextures) { Int.MIN_VALUE }

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

        val centerX = IntArray(numTextures)
        val centerY = IntArray(numTextures)
        val centerZ = IntArray(numTextures)
        val matrices = Array(numTextures) { floatArrayOf() }

        for (texIdx in 0 until numTextures) {
            val mapping = def.texturesMapping!![texIdx].toInt() and 0xff
            if (mapping > 0) {
                centerX[texIdx] = (minX[texIdx] + maxX[texIdx]) / 2
                centerY[texIdx] = (minY[texIdx] + maxY[texIdx]) / 2
                centerZ[texIdx] = (minZ[texIdx] + maxZ[texIdx]) / 2

                val complexIdx = complexIndex[texIdx]

                val scaleX: Float
                val scaleY: Float
                val scaleZ: Float

                if (mapping == 1) {
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
                    val rawX = def.texturesScaleX?.get(complexIdx) ?: 1
                    val rawY = def.texturesScaleY?.get(complexIdx) ?: 1
                    val rawZ = def.texturesScaleZ?.get(complexIdx) ?: 1
                    scaleX = 64.0f / (if (rawX == 0) 1 else rawX)
                    scaleY = 64.0f / (if (rawY == 0) 1 else rawY)
                    scaleZ = 64.0f / (if (rawZ == 0) 1 else rawZ)
                } else {
                    scaleX = (def.texturesScaleX?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleY = (def.texturesScaleY?.get(complexIdx) ?: 1024) / 1024.0f
                    scaleZ = (def.texturesScaleZ?.get(complexIdx) ?: 1024) / 1024.0f
                }

                val rotation = def.texturesRotation?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
                val n = def.textureFaceX?.get(texIdx)?.toInt() ?: 0
                val m = def.textureFaceY?.get(texIdx)?.toInt() ?: 0
                val p = def.textureFaceZ?.get(texIdx)?.toInt() ?: 0

                matrices[texIdx] = createTextureMatrix(n, m, p, rotation, scaleX, scaleY, scaleZ)
            }
        }

        return TextureTransformData(centerX, centerY, centerZ, matrices, complexIndex, cubeIndex)
    }

    private fun createTextureMatrix(n: Int, m: Int, p: Int, rotation: Int, scaleX: Float, scaleY: Float, scaleZ: Float): FloatArray {
        val rotationMap = FloatArray(9)
        val result = FloatArray(9)

        var cosR = cos(rotation * 0.024543693f)
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

        result[0] = rotationMap[0] * matrix3x3[0] + rotationMap[1] * matrix3x3[3] + rotationMap[2] * matrix3x3[6]
        result[1] = rotationMap[0] * matrix3x3[1] + rotationMap[1] * matrix3x3[4] + rotationMap[2] * matrix3x3[7]
        result[2] = rotationMap[0] * matrix3x3[2] + rotationMap[1] * matrix3x3[5] + rotationMap[2] * matrix3x3[8]
        result[3] = rotationMap[3] * matrix3x3[0] + rotationMap[4] * matrix3x3[3] + rotationMap[5] * matrix3x3[6]
        result[4] = rotationMap[3] * matrix3x3[1] + rotationMap[4] * matrix3x3[4] + rotationMap[5] * matrix3x3[7]
        result[5] = rotationMap[3] * matrix3x3[2] + rotationMap[4] * matrix3x3[5] + rotationMap[5] * matrix3x3[8]
        result[6] = rotationMap[6] * matrix3x3[0] + rotationMap[7] * matrix3x3[3] + rotationMap[8] * matrix3x3[6]
        result[7] = rotationMap[6] * matrix3x3[1] + rotationMap[7] * matrix3x3[4] + rotationMap[8] * matrix3x3[7]
        result[8] = rotationMap[6] * matrix3x3[2] + rotationMap[7] * matrix3x3[5] + rotationMap[8] * matrix3x3[8]

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

    private fun mapCylindricalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, offset: Float, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        val y = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val height = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val x = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        var u = atan2(y, x) / (2 * Math.PI.toFloat()) + 0.5f
        if (offset != 1.0f) {
            u *= offset
        }
        var v = height + 0.5f + speedOffset

        applyDirection(direction, u, v, uv)
    }

    private fun mapSphericalUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        matrix: FloatArray, direction: Int, speedOffset: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        val dist = sqrt(tx * tx + ty * ty + tz * tz)

        var u = atan2(tx, tz) / (2 * Math.PI.toFloat()) + 0.5f
        var v = asin(ty / dist) / Math.PI.toFloat() + 0.5f + speedOffset

        applyDirection(direction, u, v, uv)
    }

    private fun mapCubeUV(
        vx: Int, vy: Int, vz: Int,
        centerX: Int, centerY: Int, centerZ: Int,
        cubeFace: Int, matrix: FloatArray, direction: Int,
        speedOffset: Float, uTrans: Float, vTrans: Float,
        uv: FloatArray
    ) {
        val dx = (vx - centerX).toFloat()
        val dy = (vy - centerY).toFloat()
        val dz = (vz - centerZ).toFloat()

        val tx = dx * matrix[0] + dy * matrix[1] + dz * matrix[2]
        val ty = dx * matrix[3] + dy * matrix[4] + dz * matrix[5]
        val tz = dx * matrix[6] + dy * matrix[7] + dz * matrix[8]

        var u: Float
        var v: Float

        when (cubeFace) {
            0 -> {
                u = tx + speedOffset + 0.5f
                v = -tz + vTrans + 0.5f
            }
            1 -> {
                u = tx + speedOffset + 0.5f
                v = tz + vTrans + 0.5f
            }
            2 -> {
                u = -tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            3 -> {
                u = tx + speedOffset + 0.5f
                v = -ty + uTrans + 0.5f
            }
            4 -> {
                u = tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
            else -> {
                u = -tz + vTrans + 0.5f
                v = -ty + uTrans + 0.5f
            }
        }

        applyDirection(direction, u, v, uv)
    }

    private fun getCubeFace(nx: Float, ny: Float, nz: Float): Int {
        val ax = if (nx < 0.0f) -nx else nx
        val ay = if (ny < 0.0f) -ny else ny
        val az = if (nz < 0.0f) -nz else nz

        return if (ay > ax && ay > az) {
            if (ny > 0.0f) 0 else 1
        } else if (az > ax && az > ay) {
            if (nz > 0.0f) 2 else 3
        } else {
            if (nx > 0.0f) 4 else 5
        }
    }

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
        uv[1] = 1.0f - uv[1]
    }

    private fun computeUVs(def: ModelDefinition, faceIdx: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int = -1): FloatArray {
        val texturePointer = def.faceTexturePointers?.get(faceIdx)?.toInt()?.and(0xff) ?: 255

        if (texturePointer == 255 || texturePointer >= def.textureTriangleCount) {
            return FIXED_FALLBACK_UVS
        }

        val mappingType = def.texturesMapping?.get(texturePointer)?.toInt()?.and(0xff) ?: 0

        return when (mappingType) {
            0 -> computePlanarUVs(def, texturePointer, aIdx, bIdx, cIdx, textureId)
            1 -> computeCylindricalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            2 -> computeCubeUVs(def, faceIdx, texturePointer, aIdx, bIdx, cIdx)
            3 -> computeSphericalUVs(def, texturePointer, aIdx, bIdx, cIdx)
            else -> FIXED_FALLBACK_UVS
        }
    }

    private val FIXED_FALLBACK_UVS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

    private fun computePlanarUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int, textureId: Int): FloatArray {
        val texFaceX = def.textureFaceX ?: return FIXED_FALLBACK_UVS
        val texFaceY = def.textureFaceY ?: return FIXED_FALLBACK_UVS
        val texFaceZ = def.textureFaceZ ?: return FIXED_FALLBACK_UVS

        val tAIdx = texFaceX[texturePointer].toInt() and 0xffff
        val tBIdx = texFaceY[texturePointer].toInt() and 0xffff
        val tCIdx = texFaceZ[texturePointer].toInt() and 0xffff

        if (tAIdx >= def.vertexCount || tBIdx >= def.vertexCount || tCIdx >= def.vertexCount) {
            return FIXED_FALLBACK_UVS
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
        val aV = 1.0f - (vBasisX * v0x + vBasisY * v0y + vBasisZ * v0z) * vScale
        val bU = (uBasisX * v1x + uBasisY * v1y + uBasisZ * v1z) * uScale
        val bV = 1.0f - (vBasisX * v1x + vBasisY * v1y + vBasisZ * v1z) * vScale
        val cU = (uBasisX * v2x + uBasisY * v2y + uBasisZ * v2z) * uScale
        val cV = 1.0f - (vBasisX * v2x + vBasisY * v2y + vBasisZ * v2z) * vScale

        return floatArrayOf(aU, aV, bU, bV, cU, cV)
    }

    private fun computeCylindricalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

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

        val halfOffset = offset / 2.0f
        if ((direction and 1) == 0) {
            if (uvB[0] - uvA[0] > halfOffset) uvB[0] -= offset
            else if (uvA[0] - uvB[0] > halfOffset) uvB[0] += offset
            if (uvC[0] - uvA[0] > halfOffset) uvC[0] -= offset
            else if (uvA[0] - uvC[0] > halfOffset) uvC[0] += offset
        } else {
            if (uvB[1] - uvA[1] > halfOffset) uvB[1] -= offset
            else if (uvA[1] - uvB[1] > halfOffset) uvB[1] += offset
            if (uvC[1] - uvA[1] > halfOffset) uvC[1] -= offset
            else if (uvA[1] - uvC[1] > halfOffset) uvC[1] += offset
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    private fun computeCubeUVs(def: ModelDefinition, faceIdx: Int, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val complexIdx = transformData.complexIndex[texturePointer]
        val cubeIdx = transformData.cubeIndex[texturePointer]

        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f
        val uTrans = (def.texturesUtrans?.get(cubeIdx) ?: 0) / 256.0f
        val vTrans = (def.texturesVtrans?.get(cubeIdx) ?: 0) / 256.0f

        val dx1 = def.vertexX[bIdx] - def.vertexX[aIdx]
        val dy1 = def.vertexY[bIdx] - def.vertexY[aIdx]
        val dz1 = def.vertexZ[bIdx] - def.vertexZ[aIdx]
        val dx2 = def.vertexX[cIdx] - def.vertexX[aIdx]
        val dy2 = def.vertexY[cIdx] - def.vertexY[aIdx]
        val dz2 = def.vertexZ[cIdx] - def.vertexZ[aIdx]
        val fnx = dy1 * dz2 - dz1 * dy2
        val fny = dz1 * dx2 - dx1 * dz2
        val fnz = dx1 * dy2 - dy1 * dx2

        val rawScaleX = def.texturesScaleX?.get(complexIdx) ?: 1
        val rawScaleY = def.texturesScaleY?.get(complexIdx) ?: 1
        val rawScaleZ = def.texturesScaleZ?.get(complexIdx) ?: 1
        val scaleX = 64.0f / (if (rawScaleX == 0) 1 else rawScaleX)
        val scaleY = 64.0f / (if (rawScaleY == 0) 1 else rawScaleY)
        val scaleZ = 64.0f / (if (rawScaleZ == 0) 1 else rawScaleZ)

        val tnx = (fnx * matrix[0] + fny * matrix[1] + fnz * matrix[2]) / scaleX
        val tny = (fnx * matrix[3] + fny * matrix[4] + fnz * matrix[5]) / scaleY
        val tnz = (fnx * matrix[6] + fny * matrix[7] + fnz * matrix[8]) / scaleZ
        val cubeFace = getCubeFace(tnx, tny, tnz)

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapCubeUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvA)
        mapCubeUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvB)
        mapCubeUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, cubeFace, matrix, direction, speedOffset, uTrans, vTrans, uvC)

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    private fun computeSphericalUVs(def: ModelDefinition, texturePointer: Int, aIdx: Int, bIdx: Int, cIdx: Int): FloatArray {
        val transformResult = textureTransformCache.computeIfAbsent(def.id) {
            val data = generateTextureTransformData(def)
            if (data != null) CacheResult.Found(data) else CacheResult.NotFound
        }
        val transformData = (transformResult as? CacheResult.Found)?.value
            ?: return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val centerX = transformData.centerX[texturePointer]
        val centerY = transformData.centerY[texturePointer]
        val centerZ = transformData.centerZ[texturePointer]
        val matrix = transformData.matrix[texturePointer]
        if (matrix.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

        val complexIdx = transformData.complexIndex[texturePointer]
        val direction = def.texturesDirection?.get(complexIdx)?.toInt()?.and(0xff) ?: 0
        val speedOffset = (def.texturesSpeed?.get(complexIdx) ?: 0) / 256.0f

        val uvA = FloatArray(2)
        val uvB = FloatArray(2)
        val uvC = FloatArray(2)

        mapSphericalUV(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvA)
        mapSphericalUV(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvB)
        mapSphericalUV(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx], centerX, centerY, centerZ, matrix, direction, speedOffset, uvC)

        if ((direction and 1) == 0) {
            if (uvB[0] - uvA[0] > 0.5f) uvB[0] -= 1.0f
            else if (uvA[0] - uvB[0] > 0.5f) uvB[0] += 1.0f
            if (uvC[0] - uvA[0] > 0.5f) uvC[0] -= 1.0f
            else if (uvA[0] - uvC[0] > 0.5f) uvC[0] += 1.0f
        } else {
            if (uvB[1] - uvA[1] > 0.5f) uvB[1] -= 1.0f
            else if (uvA[1] - uvB[1] > 0.5f) uvB[1] += 1.0f
            if (uvC[1] - uvA[1] > 0.5f) uvC[1] -= 1.0f
            else if (uvA[1] - uvC[1] > 0.5f) uvC[1] += 1.0f
        }

        return floatArrayOf(uvA[0], uvA[1], uvB[0], uvB[1], uvC[0], uvC[1])
    }

    fun createTexturedModel(
        def: ModelDefinition,
        ambient: Int = ModelModifications.DEFAULT_AMBIENT,
        cache: Cache? = null,
        hideMarkerFaces: Boolean = hideUntexturedMarkerFaces
    ): TexturedModel? {
        if (def.faceCount == 0) return null


        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        val vertexNormals = accumulateVertexNormals(def)

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

        val billboardHiddenFaces = mutableSetOf<Int>()
        if (cache != null && def.billboards != null) {
            for (bb in def.billboards) {
                if (bb.appliesOnFace < 0 || bb.appliesOnFace >= def.faceCount) continue
                val type = BillboardTypeLoader.get(cache, bb.billboardId)
                if (type.hasUid) {
                    billboardHiddenFaces.add(bb.appliesOnFace)
                }
            }
        }

        val modelHasTexturedFaces = if (hideMarkerFaces) {
            def.faceTextures?.any { it.toInt() != -1 } == true
        } else {
            false
        }

        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()
        var hasAnyTextures = false

        for (i in 0 until def.faceCount) {
            if (i in billboardHiddenFaces) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            val renderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (renderType == 2) continue

            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue

            val textureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val textureId = if (textureIdSigned == -1) -1 else (textureIdSigned and 0xffff)
            if (textureId >= 0) hasAnyTextures = true

            if (modelHasTexturedFaces && textureId == -1) continue

            if (textureId >= 0 && TextureLoader.shouldSkipMaterial(textureId)) continue

            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            val rawHsl = def.faceColors[i].toInt() and 0xffff
            val hsl = if (rawHsl == 65535) rawHsl else ColorUtil.blendColorLightness(rawHsl, ambient)
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f)
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            val flatNormal = if (renderType == 0) null else faceNormalGL(def, aIdx, bIdx, cIdx)
            val norm0 = flatNormal ?: getVertexNormal(aIdx)
            val norm1 = flatNormal ?: getVertexNormal(bIdx)
            val norm2 = flatNormal ?: getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5])
            )
        }

        if (cache != null && def.billboards != null) {
            generateBillboardQuads(def, cache, ambient, facesByTexture, ::getVertexNormal, ::isValidIndex)
            hasAnyTextures = hasAnyTextures || facesByTexture.keys.any { it >= 0 }
        }

        if (facesByTexture.isEmpty()) return null

        val colorBatch = facesByTexture[-1]?.let { faces ->
            buildMesh(faces)
        }

        val textureBatches = facesByTexture
            .filter { it.key != -1 }
            .map { (textureId, faces) ->
                TextureBatch(buildMesh(faces), textureId)
            }

        return TexturedModel(colorBatch, textureBatches, hasAnyTextures)
    }

    private fun generateBillboardQuads(
        def: ModelDefinition,
        cache: Cache,
        ambient: Int,
        facesByTexture: MutableMap<Int, MutableList<FaceData>>,
        getVertexNormal: (Int) -> Vector3f,
        isValidIndex: (Int) -> Boolean
    ) {
        val billboards = def.billboards ?: return
        val scale = CoordinateUtil.DEFAULT_SCALE

        for (bb in billboards) {
            val type = BillboardTypeLoader.get(cache, bb.billboardId)

            if (type.materialId == -1) continue

            if (type.stationary) continue

            val faceIdx = bb.appliesOnFace
            if (faceIdx < 0 || faceIdx >= def.faceCount) continue

            val aIdx = def.faceA[faceIdx]
            val bIdx = def.faceB[faceIdx]
            val cIdx = def.faceC[faceIdx]
            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            val cx = (def.vertexX[aIdx] + def.vertexX[bIdx] + def.vertexX[cIdx]) / 3.0f
            val cy = (def.vertexY[aIdx] + def.vertexY[bIdx] + def.vertexY[cIdx]) / 3.0f
            val cz = (def.vertexZ[aIdx] + def.vertexZ[bIdx] + def.vertexZ[cIdx]) / 3.0f

            val center = CoordinateUtil.rsToOpenGL(cx, cy, cz)

            val edge1x = (def.vertexX[bIdx] - def.vertexX[aIdx]).toFloat()
            val edge1y = (def.vertexY[bIdx] - def.vertexY[aIdx]).toFloat()
            val edge1z = (def.vertexZ[bIdx] - def.vertexZ[aIdx]).toFloat()
            val edge2x = (def.vertexX[cIdx] - def.vertexX[aIdx]).toFloat()
            val edge2y = (def.vertexY[cIdx] - def.vertexY[aIdx]).toFloat()
            val edge2z = (def.vertexZ[cIdx] - def.vertexZ[aIdx]).toFloat()

            var nx = edge1y * edge2z - edge1z * edge2y
            var ny = edge1z * edge2x - edge1x * edge2z
            var nz = edge1x * edge2y - edge1y * edge2x

            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 0.001f) {
                nx = 0f; ny = -1f; nz = 0f; len = 1f
            }
            val invLen = 1f / len
            nx *= invLen; ny *= invLen; nz *= invLen

            val normalGL = Vector3f(nx, -ny, -nz)

            val up = if (abs(normalGL.y) < 0.99f) {
                Vector3f(0f, 1f, 0f)
            } else {
                Vector3f(1f, 0f, 0f)
            }
            val tangent = Vector3f()
            up.cross(normalGL, tangent)
            tangent.normalize()
            val bitangent = Vector3f()
            normalGL.cross(tangent, bitangent)
            bitangent.normalize()

            val halfW = (type.size2d / 2.0f) * scale
            val halfH = (type.size3d / 2.0f) * scale

            val distOffset = bb.distance.toFloat() * scale
            val offsetCenter = Vector3f(
                center.x + normalGL.x * distOffset,
                center.y + normalGL.y * distOffset,
                center.z + normalGL.z * distOffset
            )

            val v0 = Vector3f(
                offsetCenter.x - tangent.x * halfW - bitangent.x * halfH,
                offsetCenter.y - tangent.y * halfW - bitangent.y * halfH,
                offsetCenter.z - tangent.z * halfW - bitangent.z * halfH
            )
            val v1 = Vector3f(
                offsetCenter.x + tangent.x * halfW - bitangent.x * halfH,
                offsetCenter.y + tangent.y * halfW - bitangent.y * halfH,
                offsetCenter.z + tangent.z * halfW - bitangent.z * halfH
            )
            val v2 = Vector3f(
                offsetCenter.x + tangent.x * halfW + bitangent.x * halfH,
                offsetCenter.y + tangent.y * halfW + bitangent.y * halfH,
                offsetCenter.z + tangent.z * halfW + bitangent.z * halfH
            )
            val v3 = Vector3f(
                offsetCenter.x - tangent.x * halfW + bitangent.x * halfH,
                offsetCenter.y - tangent.y * halfW + bitangent.y * halfH,
                offsetCenter.z - tangent.z * halfW + bitangent.z * halfH
            )

            val rawHsl = def.faceColors[faceIdx].toInt() and 0xffff
            val hsl = if (rawHsl == 65535) rawHsl else ColorUtil.blendColorLightness(rawHsl, ambient)
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f)
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            val materialId = type.materialId

            val faces = facesByTexture.getOrPut(materialId) { mutableListOf() }
            faces.add(
                FaceData(
                    v0, v1, v2, color,
                    normalGL, normalGL, normalGL,
                    0f, 1f,  1f, 1f,  1f, 0f
                )
            )
            faces.add(
                FaceData(
                    v0, v2, v3, color,
                    normalGL, normalGL, normalGL,
                    0f, 1f,  1f, 0f,  0f, 0f
                )
            )

            val backNormal = Vector3f(-normalGL.x, -normalGL.y, -normalGL.z)
            faces.add(
                FaceData(
                    v2, v1, v0, color,
                    backNormal, backNormal, backNormal,
                    1f, 0f,  1f, 1f,  0f, 1f
                )
            )
            faces.add(
                FaceData(
                    v3, v2, v0, color,
                    backNormal, backNormal, backNormal,
                    0f, 0f,  1f, 0f,  0f, 1f
                )
            )
        }
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

    fun createMesh(def: ModelDefinition): Mesh? {
        return createTexturedModel(def)?.mesh
    }

    fun createMeshDataList(cache: Cache, modelId: Int, debug: Boolean = false): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, ModelModifications.NONE, if (debug) modelId else -1, cache)
    }

    fun createMeshDataListWithModifications(
        cache: Cache,
        modelId: Int,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        val def = loadDefinition(cache, modelId) ?: return null
        return createMeshDataListFromDef(def, modifications, if (debug) modelId else -1, cache)
    }

    fun createMeshDataListFromDef(def: ModelDefinition, debugModelId: Int = -1): List<MeshData>? {
        return createMeshDataListFromDef(def, ModelModifications.NONE, debugModelId, null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun createMeshDataListFromDef(def: ModelDefinition, modifications: ModelModifications, debugModelId: Int = -1, cache: Cache? = null): List<MeshData>? {
        val merge = modifications.normalMerge
            ?: return buildBaseMesh(def, modifications, cache)?.meshes

        val baseKey = "${def.id}|${modifications.copy(normalMerge = null).cacheKeyComponent()}|$hideUntexturedMarkerFaces"
        val existing = baseMeshCache[baseKey]
        val result = existing ?: run {
            val built = buildBaseMesh(def, modifications, cache)
            val computed = if (built != null) CacheResult.Found(built) else CacheResult.NotFound
            baseMeshCache.putIfAbsent(baseKey, computed) ?: computed
        }
        val base = (result as? CacheResult.Found)?.value ?: return null
        return applyMergeToBase(def, base, merge)
    }

    private class BaseMesh(
        val meshes: List<MeshData>,
        val slotVertex: Array<IntArray>
    )

    private val baseMeshCache = ConcurrentHashMap<String, CacheResult<BaseMesh>>()

    private fun applyMergeToBase(def: ModelDefinition, base: BaseMesh, merge: NormalMerge): List<MeshData> {
        val slotOfVertex = IntArray(def.vertexCount) { -1 }
        var touched = 0
        for (i in merge.vertexIndex.indices) {
            val idx = merge.vertexIndex[i]
            if (idx < 0 || idx >= slotOfVertex.size) continue
            slotOfVertex[idx] = i
            touched++
        }
        if (touched == 0) return base.meshes

        val out = ArrayList<MeshData>(base.meshes.size)
        for (batchIdx in base.meshes.indices) {
            val mesh = base.meshes[batchIdx]
            val normals = mesh.normals
            val slots = base.slotVertex.getOrNull(batchIdx)
            if (normals == null || slots == null) {
                out.add(mesh)
                continue
            }

            var patched: FloatArray? = null
            for (slot in slots.indices) {
                val sourceVertex = slots[slot]
                if (sourceVertex < 0 || sourceVertex >= slotOfVertex.size) continue
                val mergeSlot = slotOfVertex[sourceVertex]
                if (mergeSlot < 0) continue
                val target = patched ?: normals.copyOf().also { patched = it }
                val offset = slot * 3
                if (offset + 2 >= target.size) break
                target[offset] = merge.normalX[mergeSlot]
                target[offset + 1] = merge.normalY[mergeSlot]
                target[offset + 2] = merge.normalZ[mergeSlot]
            }

            out.add(
                if (patched == null) {
                    mesh
                } else {
                    MeshData(
                        positions = mesh.positions,
                        colors = mesh.colors,
                        textureCoords = mesh.textureCoords,
                        normals = patched,
                        indices = mesh.indices,
                        textureId = mesh.textureId
                    )
                }
            )
        }
        return out
    }

    fun smoothedNormal(sumX: Float, sumY: Float, sumZ: Float, magnitude: Int, out: Vector3f): Vector3f {
        if (magnitude == 0) return out.set(0f, 1f, 0f)

        var nx = sumX / magnitude
        var ny = sumY / magnitude
        var nz = sumZ / magnitude

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0) {
            val invLen = 1f / len
            nx *= invLen
            ny *= invLen
            nz *= invLen
        }

        return out.set(nx, -ny, -nz)
    }

    private fun buildBaseMesh(def: ModelDefinition, modifications: ModelModifications, cache: Cache?): BaseMesh? {
        if (def.faceCount == 0) return null

        fun isValidIndex(idx: Int) = idx >= 0 && idx < def.vertexCount

        val vertexNormals = accumulateVertexNormals(def)

        fun getVertexNormal(idx: Int): Vector3f {
            val vn = vertexNormals[idx]
            return smoothedNormal(vn.x, vn.y, vn.z, vn.magnitude, Vector3f())
        }

        val billboardHiddenFaces = mutableSetOf<Int>()
        if (cache != null && def.billboards != null) {
            for (bb in def.billboards) {
                if (bb.appliesOnFace < 0 || bb.appliesOnFace >= def.faceCount) continue
                val type = BillboardTypeLoader.get(cache, bb.billboardId)
                if (type.hasUid) {
                    billboardHiddenFaces.add(bb.appliesOnFace)
                }
            }
        }

        val modelHasTexturedFaces = if (hideUntexturedMarkerFaces) {
            def.faceTextures?.any { it.toInt() != -1 } == true
        } else {
            false
        }

        val facesByTexture = mutableMapOf<Int, MutableList<FaceData>>()

        for (i in 0 until def.faceCount) {
            if (i in billboardHiddenFaces) continue

            val aIdx = def.faceA[i]
            val bIdx = def.faceB[i]
            val cIdx = def.faceC[i]

            if (!isValidIndex(aIdx) || !isValidIndex(bIdx) || !isValidIndex(cIdx)) continue

            val renderType = def.faceRenderTypes?.get(i)?.toInt()?.and(0xff) ?: 0
            if (renderType == 2) continue

            val alpha = def.faceAlphas?.get(i)?.toInt()?.and(0xff) ?: 0
            if (alpha == 255) continue

            val rawTextureIdSigned = def.faceTextures?.get(i)?.toInt() ?: -1
            val rawTextureId = if (rawTextureIdSigned == -1) -1 else (rawTextureIdSigned and 0xffff)
            val textureId = if (rawTextureId >= 0) {
                modifications.applyRetexture(rawTextureId)
            } else {
                rawTextureId
            }

            if (modelHasTexturedFaces && textureId == -1) continue

            if (textureId >= 0 && TextureLoader.shouldSkipMaterial(textureId)) continue

            val pos0 = CoordinateUtil.rsToOpenGL(def.vertexX[aIdx], def.vertexY[aIdx], def.vertexZ[aIdx])
            val pos1 = CoordinateUtil.rsToOpenGL(def.vertexX[bIdx], def.vertexY[bIdx], def.vertexZ[bIdx])
            val pos2 = CoordinateUtil.rsToOpenGL(def.vertexX[cIdx], def.vertexY[cIdx], def.vertexZ[cIdx])

            val rawHsl = def.faceColors[i].toInt() and 0xffff
            val recoloredHsl = modifications.applyRecolor(rawHsl)
            val hsl = if (recoloredHsl == 65535) recoloredHsl else ColorUtil.blendColorLightness(recoloredHsl, modifications.ambient)
            val color = if (hsl == 65535) {
                Vector3f(1f, 1f, 1f)
            } else {
                ColorUtil.hslToRgb(hsl)
            }

            val uvs = if (textureId >= 0) {
                computeUVs(def, i, aIdx, bIdx, cIdx, textureId)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            }

            val flatNormal = if (renderType == 0) null else faceNormalGL(def, aIdx, bIdx, cIdx)
            val norm0 = flatNormal ?: getVertexNormal(aIdx)
            val norm1 = flatNormal ?: getVertexNormal(bIdx)
            val norm2 = flatNormal ?: getVertexNormal(cIdx)

            facesByTexture.getOrPut(textureId) { mutableListOf() }.add(
                FaceData(pos0, pos1, pos2, color, norm0, norm1, norm2,
                         uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5],
                         if (flatNormal == null) aIdx else -1,
                         if (flatNormal == null) bIdx else -1,
                         if (flatNormal == null) cIdx else -1)
            )
        }

        if (cache != null && def.billboards != null) {
            generateBillboardQuads(def, cache, modifications.ambient, facesByTexture, ::getVertexNormal, ::isValidIndex)
        }

        if (facesByTexture.isEmpty()) return null

        val meshes = ArrayList<MeshData>(facesByTexture.size)
        val slotVertex = ArrayList<IntArray>(facesByTexture.size)

        for ((textureId, faces) in facesByTexture) {
            meshes.add(buildMeshData(faces, textureId))
            slotVertex.add(buildSlotVertexMap(faces))
        }

        return BaseMesh(meshes, slotVertex.toTypedArray())
    }

    private fun buildSlotVertexMap(faces: List<FaceData>): IntArray {
        val map = IntArray(faces.size * 3)
        var slot = 0
        for (face in faces) {
            map[slot] = face.sv0
            map[slot + 1] = face.sv1
            map[slot + 2] = face.sv2
            slot += 3
        }
        return map
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
        textureTransformCache.clear()
        combinedDefinitionCache.clear()
        vertexNormalFieldCache.clear()
        baseMeshCache.clear()
        syntheticIdMapping.clear()
        nextSyntheticId.set(-1)
        BillboardTypeLoader.clearCache()
    }

    fun getCachedDefinition(modelId: Int): ModelDefinition? {
        return (definitionCache[modelId] as? CacheResult.Found)?.value
    }

    private val combinedDefinitionCache = ConcurrentHashMap<List<Int>, CacheResult<ModelDefinition>>()

    fun loadCombinedDefinition(cache: Cache, modelIds: List<Int>): ModelDefinition? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return loadDefinition(cache, modelIds[0])

        val cacheKey = modelIds.sorted()
        val result = combinedDefinitionCache.computeIfAbsent(cacheKey) {
            val models = modelIds.mapNotNull { loadDefinition(cache, it) }
            if (models.isEmpty()) return@computeIfAbsent CacheResult.NotFound

            val combinedId = -(modelIds.hashCode() and 0x7FFFFFFF)
            val combined = ModelDefinition.combine(models, combinedId)
            if (combined != null) CacheResult.Found(combined) else CacheResult.NotFound
        }
        return when (result) {
            is CacheResult.Found -> result.value
            is CacheResult.NotFound -> null
        }
    }

    fun createCombinedMeshDataList(cache: Cache, modelIds: List<Int>, debug: Boolean = false): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataList(cache, modelIds[0], debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, ModelModifications.NONE, -1, cache)
    }

    fun createCombinedMeshDataListWithModifications(
        cache: Cache,
        modelIds: List<Int>,
        modifications: ModelModifications,
        debug: Boolean = false
    ): List<MeshData>? {
        if (modelIds.isEmpty()) return null
        if (modelIds.size == 1) return createMeshDataListWithModifications(cache, modelIds[0], modifications, debug)

        val combinedDef = loadCombinedDefinition(cache, modelIds) ?: return null
        return createMeshDataListFromDef(combinedDef, modifications, -1, cache)
    }

    private val syntheticIdMapping = ConcurrentHashMap<String, Int>()
    private val nextSyntheticId = AtomicInteger(-1)

    fun getCombinedModelId(modelIds: List<Int>): Int {
        if (modelIds.size == 1) return modelIds[0]
        val key = "m_${modelIds.sorted()}"
        return syntheticIdMapping.computeIfAbsent(key) { nextSyntheticId.getAndDecrement() }
    }

    fun getCombinedModelIdWithModifications(modelIds: List<Int>, modifications: ModelModifications): Int {
        if (!modifications.hasModifications()) return getCombinedModelId(modelIds)
        val key = "x_${modelIds.sorted()}_${modifications.cacheKeyComponent()}"
        return syntheticIdMapping.computeIfAbsent(key) { nextSyntheticId.getAndDecrement() }
    }

}
