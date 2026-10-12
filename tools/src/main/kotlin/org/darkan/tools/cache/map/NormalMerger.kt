package org.darkan.tools.cache.map

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.model.NormalMerge
import org.joml.Vector3f
import world.gregs.voidps.cache.Cache
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object NormalMerger {

    private val NORMAL_QUANT: Float =
        System.getProperty("darkan.mergeNormals.quant", "128").toFloat()

    private const val HASH_SEED_A = -0x61c8864680b583ebL
    private const val HASH_SEED_B = 0x2545f4914f6cdd1dL
    private const val HASH_PRIME_A = 0x100000001b3L
    private const val HASH_PRIME_B = 0x5851f42d4c957f2dL

    private val SIGNIFICANT_COS =
        cos(Math.toRadians(System.getProperty("darkan.mergeNormals.minAngle", "2").toDouble())).toFloat()

    val enabled: Boolean = System.getProperty("darkan.mergeNormals", "true") != "false"

    private val debug: Boolean = System.getProperty("darkan.mergeNormals.debug") == "true"

    private val totalEligible = AtomicLong()
    private val totalMerged = AtomicLong()
    private val totalVariants = AtomicLong()
    private val totalNanos = AtomicLong()

    class Stats(
        val eligibleInstances: Long,
        val mergedInstances: Long,
        val distinctSignatures: Long,
        val totalMillis: Double
    )

    fun stats(): Stats = Stats(
        eligibleInstances = totalEligible.get(),
        mergedInstances = totalMerged.get(),
        distinctSignatures = totalVariants.get(),
        totalMillis = totalNanos.get() / 1_000_000.0
    )

    fun resetStats() {
        totalEligible.set(0)
        totalMerged.set(0)
        totalVariants.set(0)
        totalNanos.set(0)
    }

    private class Entries(capacity: Int) {
        var size = 0
        var key = LongArray(capacity)
        var owner = IntArray(capacity)
        var vertex = IntArray(capacity)
        var normalX = FloatArray(capacity)
        var normalY = FloatArray(capacity)
        var normalZ = FloatArray(capacity)
        var magnitude = IntArray(capacity)

        fun add(k: Long, ownerIdx: Int, vertexIdx: Int, nx: Float, ny: Float, nz: Float, mag: Int) {
            if (size == key.size) grow()
            key[size] = k
            owner[size] = ownerIdx
            vertex[size] = vertexIdx
            normalX[size] = nx
            normalY[size] = ny
            normalZ[size] = nz
            magnitude[size] = mag
            size++
        }

        private fun grow() {
            val n = if (key.size == 0) 64 else key.size * 2
            key = key.copyOf(n)
            owner = owner.copyOf(n)
            vertex = vertex.copyOf(n)
            normalX = normalX.copyOf(n)
            normalY = normalY.copyOf(n)
            normalZ = normalZ.copyOf(n)
            magnitude = magnitude.copyOf(n)
        }
    }

    fun apply(cache: Cache, instances: List<ObjectInstance>) {
        if (!enabled || instances.size < 2) return
        val startNanos = System.nanoTime()

        val ownerInstance = ArrayList<ObjectInstance>()
        val ownerField = ArrayList<ModelLoader.VertexNormalField>()
        var vertexBudget = 0
        for (obj in instances) {
            if (!participates(obj)) continue
            val field = ModelLoader.vertexNormalField(cache, obj.modelIds) ?: continue
            ownerInstance.add(obj)
            ownerField.add(field)
            vertexBudget += field.vertexCount
        }
        if (ownerInstance.size < 2) return

        val entries = Entries(vertexBudget.coerceAtLeast(64))
        for (ownerIdx in ownerInstance.indices) {
            collectVertices(ownerInstance[ownerIdx], ownerField[ownerIdx], ownerIdx, entries)
        }
        if (entries.size < 2) return

        val head = Long2IntOpenHashMap(entries.size)
        head.defaultReturnValue(-1)
        val next = IntArray(entries.size)
        for (e in 0 until entries.size) {
            next[e] = head.put(entries.key[e], e)
        }

        val addX = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addY = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addZ = arrayOfNulls<FloatArray>(ownerInstance.size)
        val addMag = arrayOfNulls<IntArray>(ownerInstance.size)

        val chainHeads = head.values.toIntArray()
        for (chainHead in chainHeads) {
            if (next[chainHead] == -1) continue

            var totalX = 0f
            var totalY = 0f
            var totalZ = 0f
            var totalMag = 0
            var distinctOwner = -1
            var singleOwner = true
            var e = chainHead
            while (e != -1) {
                totalX += entries.normalX[e]
                totalY += entries.normalY[e]
                totalZ += entries.normalZ[e]
                totalMag += entries.magnitude[e]
                if (distinctOwner == -1) {
                    distinctOwner = entries.owner[e]
                } else if (entries.owner[e] != distinctOwner) {
                    singleOwner = false
                }
                e = next[e]
            }
            if (singleOwner) continue

            e = chainHead
            while (e != -1) {
                val ownerIdx = entries.owner[e]

                var selfX = 0f
                var selfY = 0f
                var selfZ = 0f
                var selfMag = 0
                var s = chainHead
                while (s != -1) {
                    if (entries.owner[s] == ownerIdx) {
                        selfX += entries.normalX[s]
                        selfY += entries.normalY[s]
                        selfZ += entries.normalZ[s]
                        selfMag += entries.magnitude[s]
                    }
                    s = next[s]
                }

                val contribMag = totalMag - selfMag
                if (contribMag != 0) {
                    val obj = ownerInstance[ownerIdx]
                    val field = ownerField[ownerIdx]
                    val v = entries.vertex[e]

                    val wx = totalX - selfX
                    val wy = totalY - selfY
                    val wz = totalZ - selfZ
                    val localX = unrotateX(wx, wz, obj.rsRotation)
                    val localZ = unrotateZ(wx, wz, obj.rsRotation, obj.mirrored)

                    if (deflects(field, v, localX, wy, localZ, contribMag)) {
                        val count = field.vertexCount
                        val ax = addX[ownerIdx] ?: FloatArray(count).also { addX[ownerIdx] = it }
                        val ay = addY[ownerIdx] ?: FloatArray(count).also { addY[ownerIdx] = it }
                        val az = addZ[ownerIdx] ?: FloatArray(count).also { addZ[ownerIdx] = it }
                        val am = addMag[ownerIdx] ?: IntArray(count).also { addMag[ownerIdx] = it }
                        ax[v] += localX
                        ay[v] += wy
                        az[v] += localZ
                        am[v] += contribMag
                    }
                }
                e = next[e]
            }
        }

        var mergedCount = 0
        val signatures = HashSet<String>()
        val scratch = Vector3f()
        for (ownerIdx in ownerInstance.indices) {
            val mags = addMag[ownerIdx] ?: continue
            val merge = buildMerge(
                ownerField[ownerIdx], addX[ownerIdx]!!, addY[ownerIdx]!!, addZ[ownerIdx]!!, mags, scratch
            ) ?: continue
            val obj = ownerInstance[ownerIdx]
            obj.modifications = obj.modifications.withNormalMerge(merge)
            obj.cachedCombinedModelId =
                ModelLoader.getCombinedModelIdWithModifications(obj.modelIds, obj.modifications)
            mergedCount++
            signatures.add(merge.signature)
        }

        totalEligible.addAndGet(ownerInstance.size.toLong())
        totalMerged.addAndGet(mergedCount.toLong())
        totalVariants.addAndGet(signatures.size.toLong())
        val elapsed = System.nanoTime() - startNanos
        totalNanos.addAndGet(elapsed)

        if (debug) {
            println(
                "[NORMAL-MERGE] eligible=${ownerInstance.size} merged=$mergedCount " +
                    "signatures=${signatures.size} vertices=${entries.size} " +
                    "in ${"%.2f".format(elapsed / 1_000_000.0)}ms"
            )
        }
    }

    private fun deflects(
        field: ModelLoader.VertexNormalField,
        v: Int,
        addX: Float,
        addY: Float,
        addZ: Float,
        addMag: Int
    ): Boolean {
        val baseMag = field.magnitude[v]
        if (baseMag == 0) return false

        val bx = field.normalX[v] / baseMag
        val by = field.normalY[v] / baseMag
        val bz = field.normalZ[v] / baseMag
        val baseLen = sqrt(bx * bx + by * by + bz * bz)
        if (baseLen <= 0f) return false

        val mergedMag = baseMag + addMag
        if (mergedMag == 0) return true
        val mx = (field.normalX[v] + addX) / mergedMag
        val my = (field.normalY[v] + addY) / mergedMag
        val mz = (field.normalZ[v] + addZ) / mergedMag
        val mergedLen = sqrt(mx * mx + my * my + mz * mz)
        if (mergedLen <= 0f) return true

        val dot = (bx * mx + by * my + bz * mz) / (baseLen * mergedLen)
        return dot < SIGNIFICANT_COS
    }

    private fun participates(obj: ObjectInstance): Boolean {
        if (!obj.definition.delayShading) return false
        if (obj.terrainAlignment.needsAlignment()) return false
        return obj.modelIds.isNotEmpty()
    }

    private fun collectVertices(
        obj: ObjectInstance,
        field: ModelLoader.VertexNormalField,
        ownerIdx: Int,
        entries: Entries
    ) {
        val def = obj.definition
        val resizeX = def.modelSizeX
        val resizeY = def.modelSizeZ
        val resizeZ = def.modelSizeY

        val mirrorSign = if (obj.mirrored) -1 else 1
        val factorXX: Int
        val factorXZ: Int
        val factorZX: Int
        val factorZZ: Int
        when (obj.rsRotation and 3) {
            1 -> { factorXX = 0; factorXZ = resizeX * mirrorSign; factorZX = -resizeZ; factorZZ = 0 }
            2 -> { factorXX = -resizeX; factorXZ = 0; factorZX = 0; factorZZ = -resizeZ * mirrorSign }
            3 -> { factorXX = 0; factorXZ = -resizeX * mirrorSign; factorZX = resizeZ; factorZZ = 0 }
            else -> { factorXX = resizeX; factorXZ = 0; factorZX = 0; factorZZ = resizeZ * mirrorSign }
        }

        val baseX = def.offsetX + obj.rsSceneX
        val baseZ = def.offsetY + obj.rsSceneY
        val baseY = obj.rsAverageHeight - def.offsetZ

        for (v in 0 until field.vertexCount) {
            val mag = field.magnitude[v]
            if (mag == 0) continue

            val rsX = field.vertexX[v]
            val rsY = field.vertexY[v]
            val rsZ = field.vertexZ[v]

            val sceneX = (((factorXX * rsX) + (factorXZ * rsZ)) shr 7) + baseX
            val sceneZ = (((factorZX * rsX) + (factorZZ * rsZ)) shr 7) + baseZ
            val sceneY = baseY - ((resizeY * rsY) shr 7)

            val nx = field.normalX[v]
            val ny = field.normalY[v]
            val nz = field.normalZ[v]

            entries.add(
                packKey(sceneX, sceneY, sceneZ),
                ownerIdx,
                v,
                rotateX(nx, nz, obj.rsRotation, obj.mirrored),
                ny,
                rotateZ(nx, nz, obj.rsRotation, obj.mirrored),
                mag
            )
        }
    }

    private fun buildMerge(
        field: ModelLoader.VertexNormalField,
        addX: FloatArray,
        addY: FloatArray,
        addZ: FloatArray,
        addMag: IntArray,
        scratch: Vector3f
    ): NormalMerge? {
        var used = 0
        for (mag in addMag) {
            if (mag != 0) used++
        }
        if (used == 0) return null

        val indices = IntArray(used)
        val outX = FloatArray(used)
        val outY = FloatArray(used)
        val outZ = FloatArray(used)

        var hashA = HASH_SEED_A
        var hashB = HASH_SEED_B
        var out = 0
        for (v in addMag.indices) {
            if (addMag[v] == 0) continue

            val merged = ModelLoader.smoothedNormal(
                field.normalX[v] + addX[v],
                field.normalY[v] + addY[v],
                field.normalZ[v] + addZ[v],
                field.magnitude[v] + addMag[v],
                scratch
            )

            val qx = (merged.x * NORMAL_QUANT).roundToInt()
            val qy = (merged.y * NORMAL_QUANT).roundToInt()
            val qz = (merged.z * NORMAL_QUANT).roundToInt()

            var nx = qx.toFloat()
            var ny = qy.toFloat()
            var nz = qz.toFloat()
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) {
                val invLen = 1f / len
                nx *= invLen
                ny *= invLen
                nz *= invLen
            }

            indices[out] = v
            outX[out] = nx
            outY[out] = ny
            outZ[out] = nz

            val packed = (v.toLong() shl 48) xor
                ((qx.toLong() and 0xFFFFL) shl 32) xor
                ((qy.toLong() and 0xFFFFL) shl 16) xor
                (qz.toLong() and 0xFFFFL)
            hashA = mix(hashA, packed, HASH_PRIME_A)
            hashB = mix(hashB, packed, HASH_PRIME_B)
            out++
        }

        val signature = hashA.toULong().toString(16) + ':' + hashB.toULong().toString(16)
        return NormalMerge(indices, outX, outY, outZ, signature)
    }

    private fun mix(hash: Long, value: Long, prime: Long): Long {
        var h = hash xor value
        h *= prime
        h = h xor (h ushr 31)
        return h
    }

    private fun packKey(x: Int, y: Int, z: Int): Long {
        val px = (x + 131072).toLong() and 0x3FFFFL
        val pz = (z + 131072).toLong() and 0x3FFFFL
        val py = (y + 524288).toLong() and 0xFFFFFL
        return (px shl 38) or (pz shl 20) or py
    }

    private fun rotateX(nx: Float, nz: Float, rotation: Int, mirrored: Boolean): Float {
        val mz = if (mirrored) -nz else nz
        return when (rotation and 3) {
            1 -> mz
            2 -> -nx
            3 -> -mz
            else -> nx
        }
    }

    private fun rotateZ(nx: Float, nz: Float, rotation: Int, mirrored: Boolean): Float {
        val mz = if (mirrored) -nz else nz
        return when (rotation and 3) {
            1 -> -nx
            2 -> -mz
            3 -> nx
            else -> mz
        }
    }

    private fun unrotateX(wx: Float, wz: Float, rotation: Int): Float {
        return when ((4 - (rotation and 3)) and 3) {
            1 -> wz
            2 -> -wx
            3 -> -wz
            else -> wx
        }
    }

    private fun unrotateZ(wx: Float, wz: Float, rotation: Int, mirrored: Boolean): Float {
        val z = when ((4 - (rotation and 3)) and 3) {
            1 -> -wx
            2 -> -wz
            3 -> wx
            else -> wz
        }
        return if (mirrored) -z else z
    }
}
