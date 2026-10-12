package org.darkan.tools.engine.render

import org.darkan.tools.cache.texture.TexturePixelStats
import org.joml.Matrix4f
import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL12.glTexSubImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
import org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL33.glVertexAttribDivisor
import org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.glCopyImageSubData
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect
import org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT
import org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.system.MemoryUtil
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

class IndirectRenderer(
    private val texturePixelProvider: TexturePixelProvider? = null
) {

    private var sharedVaoId: Int = 0

    private var sharedIboId: Int = 0

    private var totalVertexCount: Int = 0

    private var totalIndexCount: Int = 0

    private val positionAccumulator = mutableListOf<FloatArray>()
    private val colorAccumulator = mutableListOf<FloatArray>()
    private val texCoordAccumulator = mutableListOf<FloatArray?>()
    private val normalAccumulator = mutableListOf<FloatArray>()
    private val indexAccumulator = mutableListOf<IntArray>()


    private class MeshEntry(
        val indexCount: Int,
        val firstIndex: Int,
        val baseVertex: Int,
        val textureId: Int,
        val localBounds: FloatArray,
        val vertexCount: Int
    ) {
        var textureArrayIndex: Int = -1

        var textureLayer: Int = -1
    }

    private class ResolvedModelBatch(val meshEntryIndices: IntArray)

    private val meshEntries = mutableListOf<MeshEntry>()

    private val modelRegistry = mutableMapOf<Int, ResolvedModelBatch>()


    private val droppedModelIds = LinkedHashSet<Int>()

    var droppedInstanceCount = 0
        private set

    val droppedModelIdCount: Int get() = droppedModelIds.size

    fun droppedModelIdList(): List<Int> = droppedModelIds.toList()

    private fun noteMissingModel(modelId: Int, site: String) {
        droppedInstanceCount++
        if (!droppedModelIds.add(modelId)) return
        if (droppedModelIds.size <= MAX_DROP_LOG_LINES) {
            System.err.println(
                "[IndirectRenderer] $site: model $modelId has no registered geometry, " +
                        "its instances will not be drawn"
            )
        } else if (droppedModelIds.size == MAX_DROP_LOG_LINES + 1) {
            System.err.println(
                "[IndirectRenderer] further missing models suppressed; see the dropped " +
                        "instance counter in the debug panel for the running total"
            )
        }
    }


    private var colorSsbo: PersistentBuffer? = null
    private var texturedSsbo: PersistentBuffer? = null
    private var colorIndirectBuffer: PersistentBuffer? = null
    private var texturedIndirectBuffer: PersistentBuffer? = null

    private var colorInstanceCapacity = 0
    private var texturedInstanceCapacity = 0

    private var colorCommandCapacity = 0
    private var texturedCommandCapacity = 0


    private class ArrayTexture(val glId: Int, val size: Int, val layers: Int)

    private val arrayTextures = mutableListOf<ArrayTexture>()

    private val materialSlots = HashMap<Int, Int>()

    private val singleTexturedDraw: Boolean
        get() = arrayTextures.size in 1..MAX_TEXTURE_ARRAYS

    private var missingTextureCount = 0


    private val colorInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()
    private val texturedInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()

    private class TexturedBucket(var firstCommand: Int = 0, var commandCount: Int = 0)

    private var texturedBuckets = emptyArray<TexturedBucket>()

    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0
    private var commandsThisFrame = 0
    private var initialized = false
    private var geometryBuilt = false

    private var colorCommandCount = 0
    private var colorInstanceBytes = 0L
    private var texturedCommandCount = 0
    private var texturedInstanceBytes = 0L


    companion object {
        private const val INDIRECT_COMMAND_SIZE = 20

        const val MAX_TEXTURE_ARRAYS = 4

        fun textureArrayUniformNames(): Array<String> =
            Array(MAX_TEXTURE_ARRAYS) { "textureArrays[$it]" }

        private val QUANTISE_COLORS: Boolean =
            System.getProperty("darkan.indirectByteColors") != "false"

        private val THREAD_MX = ManagementFactory.getThreadMXBean()

        private val CPU_TIME_SUPPORTED: Boolean =
            THREAD_MX.isCurrentThreadCpuTimeSupported && THREAD_MX.isThreadCpuTimeEnabled

        private val AUDIT: Boolean = System.getProperty("darkan.mdiAudit", "false").toBoolean()

        private val REVERSE_COMMANDS: Boolean = System.getProperty("darkan.mdiReverse", "false").toBoolean()

        private const val TEXTURED_INSTANCE_STRIDE = 80

        private const val COLOR_INSTANCE_STRIDE = TEXTURED_INSTANCE_STRIDE

        private const val INSTANCE_SSBO_BINDING = 1

        private const val VISIBLE_SSBO_BINDING = 2

        private const val CULL_BOUNDS_BINDING = 3
        private const val CULL_ITEM_META_BINDING = 4
        private const val CULL_COMMAND_BINDING = 5
        private const val CULL_STATS_BINDING = 6

        private const val COMPUTE_GROUP_SIZE = 64

        private const val STATS_SLOTS = 4

        private const val STATS_UINTS_PER_SLOT = 2

        private val STATS_FORCED: Boolean = System.getProperty("darkan.cullStats", "false").toBoolean()

        private const val STATS_REQUEST_LINGER = 60

        @Volatile
        private var statsRequestFramesLeft = 0

        fun requestCullStats() {
            statsRequestFramesLeft = STATS_REQUEST_LINGER
        }

        private const val STATS_SAMPLE_INTERVAL = 15

        private const val INSTANCE_STRIDE = TEXTURED_INSTANCE_STRIDE

        private const val BOUND_SLACK = 1.02f

        private const val CAPACITY_GRANULARITY = 1024

        private const val MAX_DROP_LOG_LINES = 16

        fun isComputeSupported(): Boolean {
            val caps = GL.getCapabilities()
            return caps.OpenGL43 || caps.GL_ARB_compute_shader
        }

        private fun dispatchGroups(items: Int): Int =
            (items + COMPUTE_GROUP_SIZE - 1) / COMPUTE_GROUP_SIZE

        private fun computeLocalBounds(positions: FloatArray): FloatArray {
            if (positions.size < 3) return FloatArray(0)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i + 2 < positions.size) {
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
                i += 3
            }
            return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
        }

        private fun createBuffer(target: Int, data: ByteBuffer, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        private fun createIntBuffer(target: Int, data: IntArray, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        private fun createFloatBuffer(target: Int, data: FloatArray, usage: Int): Int {
            val id = glGenBuffers()
            glBindBuffer(target, id)
            glBufferData(target, data, usage)
            glBindBuffer(target, 0)
            return id
        }

        fun isSupported(): Boolean {
            val caps = GL.getCapabilities()
            val hasIndirect = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect
            val hasSsbo = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object
            val hasDrawParams = caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters
            if (!hasIndirect || !hasSsbo || !hasDrawParams) {
                println(
                    "[IndirectRenderer] unsupported GL context: " +
                            "version=${glGetString(GL_VERSION)} multiDrawIndirect=$hasIndirect " +
                            "ssbo=$hasSsbo shaderDrawParameters=$hasDrawParams"
                )
                return false
            }
            return true
        }

        private fun growCapacity(needed: Int): Int {
            val withHeadroom = needed + (needed / 4) + 1
            return ((withHeadroom + CAPACITY_GRANULARITY - 1) / CAPACITY_GRANULARITY) * CAPACITY_GRANULARITY
        }
    }


    fun init() {
        sharedVaoId = glGenVertexArrays()
        initialized = true
        IndirectReuseStats.beginBuild()
    }


    fun registerModel(modelId: Int, meshDataList: List<MeshData>) {
        if (modelRegistry.containsKey(modelId)) return

        val registerStart = if (IndirectReuseStats.ENABLED) System.nanoTime() else 0L
        val registerCpuStart = if (IndirectReuseStats.ENABLED) threadCpuNanos() else 0L
        val entryIndices = mutableListOf<Int>()

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            val numVertices = meshData.positions.size / 3
            val textureId = meshData.textureId

            val baseVertex = totalVertexCount
            val firstIndex = totalIndexCount

            positionAccumulator.add(meshData.positions)

            colorAccumulator.add(meshData.colors ?: FloatArray(meshData.positions.size) { 1f })

            texCoordAccumulator.add(meshData.textureCoords)

            normalAccumulator.add(meshData.normals ?: FloatArray(meshData.positions.size) { i ->
                when (i % 3) { 1 -> 1f; else -> 0f }
            })

            indexAccumulator.add(meshData.indices)

            totalVertexCount += numVertices
            totalIndexCount += meshData.indices.size

            entryIndices.add(meshEntries.size)
            meshEntries.add(MeshEntry(
                indexCount = meshData.indices.size,
                firstIndex = firstIndex,
                baseVertex = baseVertex,
                textureId = textureId,
                localBounds = computeLocalBounds(meshData.positions),
                vertexCount = numVertices
            ))
        }

        if (entryIndices.isNotEmpty()) {
            modelRegistry[modelId] = ResolvedModelBatch(entryIndices.toIntArray())
        }

        if (IndirectReuseStats.ENABLED) {
            var verts = 0
            var idx = 0
            for (entryIdx in entryIndices) {
                val entry = meshEntries[entryIdx]
                idx += entry.indexCount
                verts += entry.vertexCount
            }
            IndirectReuseStats.recordModel(modelId, verts, idx)
            registerAccumulateMs += (System.nanoTime() - registerStart) / 1e6
            registerAccumulateCpuMs += (threadCpuNanos() - registerCpuStart) / 1e6
        }
    }

    fun registerModelLate(modelId: Int, meshDataList: List<MeshData>): Boolean {
        if (modelRegistry.containsKey(modelId)) return true
        if (!geometryBuilt) {
            registerModel(modelId, meshDataList)
            return modelRegistry.containsKey(modelId)
        }

        val start = System.nanoTime()
        val firstNewEntry = meshEntries.size
        val baseVertex = totalVertexCount
        val baseIndex = totalIndexCount

        registerModel(modelId, meshDataList)
        if (!modelRegistry.containsKey(modelId)) {
            clearGeometryAccumulators()
            return false
        }

        appendSharedGeometry(baseVertex, baseIndex)
        resolveLateMaterials(firstNewEntry)

        droppedModelIds.remove(modelId)

        println(
            "[IndirectRenderer] ingested model $modelId after the build: " +
                    "${meshEntries.size - firstNewEntry} batch(es), " +
                    "${totalVertexCount - baseVertex} vertices, " +
                    "${totalIndexCount - baseIndex} indices in " +
                    "%.1f ms".format((System.nanoTime() - start) / 1e6)
        )
        return true
    }

    private fun clearGeometryAccumulators() {
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()
    }

    private fun appendSharedGeometry(baseVertex: Int, baseIndex: Int) {
        val addedVertices = totalVertexCount - baseVertex
        val addedIndices = totalIndexCount - baseIndex
        if (addedVertices <= 0 || addedIndices <= 0 || vboIds.size < 4) {
            clearGeometryAccumulators()
            return
        }

        glBindVertexArray(sharedVaoId)

        vboIds[0] = growFloatAttribute(vboIds[0], 0, 3, baseVertex, addedVertices) { buf ->
            for (arr in positionAccumulator) buf.put(arr)
        }
        vboIds[1] = if (QUANTISE_COLORS) {
            growColorAttribute(vboIds[1], 1, baseVertex, addedVertices)
        } else {
            growFloatAttribute(vboIds[1], 1, 3, baseVertex, addedVertices) { buf ->
                for (arr in colorAccumulator) buf.put(arr)
            }
        }
        vboIds[2] = growFloatAttribute(vboIds[2], 2, 2, baseVertex, addedVertices) { buf ->
            for (i in texCoordAccumulator.indices) {
                val tc = texCoordAccumulator[i]
                if (tc != null) {
                    buf.put(tc)
                } else {
                    repeat((positionAccumulator[i].size / 3) * 2) { buf.put(0f) }
                }
            }
        }
        vboIds[3] = growFloatAttribute(vboIds[3], 3, 3, baseVertex, addedVertices) { buf ->
            for (arr in normalAccumulator) buf.put(arr)
        }

        growIndexBuffer(baseIndex, addedIndices)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        clearGeometryAccumulators()

        val colorBytes = if (QUANTISE_COLORS) MeshBuffers.COLOR_BYTES else 3 * Float.SIZE_BYTES
        val vertexBytes = (3 + 2 + 3) * Float.SIZE_BYTES + colorBytes
        sharedGeometryBytes =
            totalVertexCount.toLong() * vertexBytes + totalIndexCount.toLong() * Int.SIZE_BYTES
    }

    private fun growVertexAttribute(
        oldVbo: Int,
        location: Int,
        components: Int,
        type: Int,
        normalized: Boolean,
        bytesPerVertex: Int,
        existingVertices: Int,
        addedVertices: Int,
        uploadTail: (target: Int, offset: Long) -> Unit
    ): Int {
        val existingBytes = existingVertices.toLong() * bytesPerVertex
        val totalBytes = existingBytes + addedVertices.toLong() * bytesPerVertex

        val newVbo = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, newVbo)
        glBufferData(GL_COPY_WRITE_BUFFER, totalBytes, GL_STATIC_DRAW)
        if (existingBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, oldVbo)
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingBytes)
            glBindBuffer(GL_COPY_READ_BUFFER, 0)
        }
        uploadTail(GL_COPY_WRITE_BUFFER, existingBytes)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
        glDeleteBuffers(oldVbo)

        glBindBuffer(GL_ARRAY_BUFFER, newVbo)
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(location, components, type, normalized, 0, 0)
        glVertexAttribDivisor(location, 0)
        return newVbo
    }

    private fun growFloatAttribute(
        oldVbo: Int,
        location: Int,
        components: Int,
        existingVertices: Int,
        addedVertices: Int,
        fill: (FloatBuffer) -> Unit
    ): Int {
        val tail = MemoryUtil.memAllocFloat(addedVertices * components)
        try {
            fill(tail)
            tail.flip()
            return growVertexAttribute(
                oldVbo, location, components, GL_FLOAT, false,
                components * Float.SIZE_BYTES, existingVertices, addedVertices
            ) { target, offset -> glBufferSubData(target, offset, tail) }
        } finally {
            MemoryUtil.memFree(tail)
        }
    }

    private fun growColorAttribute(
        oldVbo: Int,
        location: Int,
        existingVertices: Int,
        addedVertices: Int
    ): Int {
        val totalBytes = addedVertices * MeshBuffers.COLOR_BYTES
        val tail = MemoryUtil.memAlloc(totalBytes).order(ByteOrder.nativeOrder())
        try {
            val baseAddress = MemoryUtil.memAddress(tail)
            var vertexOffset = 0
            for (arr in colorAccumulator) {
                val vertices = arr.size / 3
                MeshBuffers.writeColorBytes(
                    baseAddress + vertexOffset.toLong() * MeshBuffers.COLOR_BYTES,
                    arr, vertices, 3
                )
                vertexOffset += vertices
            }
            tail.position(0)
            tail.limit(totalBytes)
            return growVertexAttribute(
                oldVbo, location, 4, GL_UNSIGNED_BYTE, true,
                MeshBuffers.COLOR_BYTES, existingVertices, addedVertices
            ) { target, offset -> glBufferSubData(target, offset, tail) }
        } finally {
            MemoryUtil.memFree(tail)
        }
    }

    private fun growIndexBuffer(existingIndices: Int, addedIndices: Int) {
        val existingBytes = existingIndices.toLong() * Int.SIZE_BYTES
        val totalBytes = existingBytes + addedIndices.toLong() * Int.SIZE_BYTES

        val newIbo = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, newIbo)
        glBufferData(GL_COPY_WRITE_BUFFER, totalBytes, GL_STATIC_DRAW)
        if (existingBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, sharedIboId)
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingBytes)
            glBindBuffer(GL_COPY_READ_BUFFER, 0)
        }
        val tail = MemoryUtil.memAllocInt(addedIndices)
        try {
            for (arr in indexAccumulator) tail.put(arr)
            tail.flip()
            glBufferSubData(GL_COPY_WRITE_BUFFER, existingBytes, tail)
        } finally {
            MemoryUtil.memFree(tail)
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)

        val oldIbo = sharedIboId
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, newIbo)
        sharedIboId = newIbo
        if (oldIbo != 0) glDeleteBuffers(oldIbo)
    }

    private fun resolveLateMaterials(fromEntry: Int) {
        val provider = texturePixelProvider
        val arraysBefore = arrayTextures.size

        if (provider != null) {
            val needed = LinkedHashSet<Int>()
            for (i in fromEntry until meshEntries.size) {
                val entry = meshEntries[i]
                if (entry.textureId >= 0 && !materialSlots.containsKey(entry.textureId)) {
                    needed.add(entry.textureId)
                }
            }
            if (needed.isNotEmpty()) {
                val bySize = LinkedHashMap<Int, MutableList<Pair<Int, IntArray>>>()
                for (id in needed) {
                    val pixelData = provider.getPixels(id)
                    if (pixelData == null || pixelData.second != pixelData.third) {
                        missingTextureCount++
                        val size = arrayTextures.maxOfOrNull { it.size } ?: 64
                        bySize.getOrPut(size) { mutableListOf() }.add(id to checkerPixels(size))
                    } else {
                        bySize.getOrPut(pixelData.second) { mutableListOf() }
                            .add(id to pixelData.first)
                    }
                }
                for ((size, materials) in bySize) appendMaterialLayers(size, materials)
                println(
                    "[IndirectRenderer] ingest added ${needed.size} material layer(s): " +
                            bySize.entries.joinToString { "${it.value.size}x${it.key}px" }
                )
            }
        }

        assignMaterialSlots(fromEntry)

        if (arrayTextures.size != arraysBefore) {
            texturedBuckets = Array(arrayTextures.size) { TexturedBucket() }
            println(
                "[IndirectRenderer] array textures after ingest: " +
                        arrayTextures.joinToString { "${it.size}px x${it.layers}" }
            )
        }
    }

    private fun assignMaterialSlots(fromEntry: Int) {
        for (i in fromEntry until meshEntries.size) {
            val entry = meshEntries[i]
            if (entry.textureId < 0) continue
            val packed = materialSlots[entry.textureId] ?: continue
            entry.textureArrayIndex = (packed shr 16)
            entry.textureLayer = packed and 0xFFFF
        }
    }

    private fun appendMaterialLayers(size: Int, materials: List<Pair<Int, IntArray>>) {
        val maxLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS).coerceAtLeast(1)
        var remaining = materials
        while (remaining.isNotEmpty()) {
            val target =
                if (canCopyImages()) arrayTextures.indexOfFirst { it.size == size && it.layers < maxLayers }
                else -1

            val chunk: List<Pair<Int, IntArray>>
            if (target >= 0) {
                chunk = remaining.take(maxLayers - arrayTextures[target].layers)
                if (!growArrayTexture(target, chunk)) {
                    addMaterialArray(size, chunk)
                }
            } else {
                chunk = remaining.take(maxLayers)
                addMaterialArray(size, chunk)
            }
            remaining = remaining.drop(chunk.size)
        }
    }

    private fun addMaterialArray(size: Int, materials: List<Pair<Int, IntArray>>) {
        val arrayIndex = arrayTextures.size
        val glId = createArrayTexture(size, materials.map { it.second })
        arrayTextures.add(ArrayTexture(glId, size, materials.size))
        for ((layer, pair) in materials.withIndex()) {
            materialSlots[pair.first] = (arrayIndex shl 16) or layer
            IndirectReuseStats.recordMaterial(pair.first, size)
        }
    }

    private fun growArrayTexture(index: Int, materials: List<Pair<Int, IntArray>>): Boolean {
        val existing = arrayTextures[index]
        val size = existing.size
        val texCpuStart = threadCpuNanos()

        val texId = createEmptyArrayTexture(size, existing.layers + materials.size)
        while (glGetError() != GL_NO_ERROR) { }
        glCopyImageSubData(
            existing.glId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
            texId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
            size, size, existing.layers
        )
        val copyError = glGetError()
        if (copyError != GL_NO_ERROR) {
            System.err.println(
                "[IndirectRenderer] could not copy ${existing.layers} layer(s) of the ${size}px " +
                        "array texture into its replacement (GL error 0x${copyError.toString(16)}); " +
                        "giving the new materials an array of their own"
            )
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
            glDeleteTextures(texId)
            return false
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, texId)
        uploadArrayLayers(size, existing.layers, materials.map { it.second })
        applyArrayTextureState()
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        texArrayCpuTimeMs += (threadCpuNanos() - texCpuStart) / 1e6

        glDeleteTextures(existing.glId)
        arrayTextures[index] = ArrayTexture(texId, size, existing.layers + materials.size)
        for ((offset, pair) in materials.withIndex()) {
            materialSlots[pair.first] = (index shl 16) or (existing.layers + offset)
            IndirectReuseStats.recordMaterial(pair.first, size)
        }
        return true
    }

    private fun canCopyImages(): Boolean {
        val caps = GL.getCapabilities()
        return caps.OpenGL43 || caps.GL_ARB_copy_image
    }

    private fun threadCpuNanos(): Long =
        if (CPU_TIME_SUPPORTED) THREAD_MX.currentThreadCpuTime else 0L

    private var registerAccumulateMs = 0.0

    private var registerAccumulateCpuMs = 0.0

    private var geometryUploadCpuMs = 0.0

    private var texArrayCpuTimeMs = 0.0

    private var texArrayCpuMs = 0.0

    private var texArrayUploadMs = 0.0

    private var texArrayMipmapMs = 0.0

    fun isModelRegistered(modelId: Int): Boolean = modelRegistry.containsKey(modelId)

    private fun isTextured(entry: MeshEntry): Boolean = entry.textureArrayIndex >= 0

    private fun batchOrderKey(entryIdx: Int): Long {
        val entry = meshEntries[entryIdx]
        return (entry.textureId.toLong() shl 32) or entryIdx.toLong()
    }

    private fun texturedCommandOrder(entries: Collection<Int>): List<Int> =
        if (singleTexturedDraw) {
            entries.sortedBy { batchOrderKey(it) }
        } else {
            entries.sortedWith(compareBy({ meshEntries[it].textureArrayIndex }, { batchOrderKey(it) }))
        }

    private fun bindArrayTextures(shader: ShaderProgram) {
        for (i in 0 until MAX_TEXTURE_ARRAYS) {
            val unit = if (i < arrayTextures.size) i else 0
            if (i < arrayTextures.size) {
                glActiveTexture(GL_TEXTURE0 + i)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[i].glId)
            }
            shader.setUniform("textureArrays[$i]", unit)
        }
        glActiveTexture(GL_TEXTURE0)
    }

    private fun instanceArraySlot(entry: MeshEntry): Int =
        if (singleTexturedDraw) entry.textureArrayIndex else 0

    private fun unbindArrayTextures() {
        for (i in arrayTextures.indices) {
            if (i >= MAX_TEXTURE_ARRAYS) break
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        }
        glActiveTexture(GL_TEXTURE0)
    }


    fun buildSharedGeometry() {
        if (totalVertexCount == 0 || totalIndexCount == 0) return

        val uploadStart = System.nanoTime()
        val uploadCpuStart = threadCpuNanos()
        glBindVertexArray(sharedVaoId)

        val posVbo = uploadFloatAttribute(0, 3, totalVertexCount * 3) { buf ->
            for (arr in positionAccumulator) buf.put(arr)
        }
        val colorVbo = if (QUANTISE_COLORS) uploadColorAttribute(1, totalVertexCount)
        else uploadFloatAttribute(1, 3, totalVertexCount * 3) { buf ->
            for (arr in colorAccumulator) buf.put(arr)
        }
        val texVbo = uploadFloatAttribute(2, 2, totalVertexCount * 2) { buf ->
            for (i in texCoordAccumulator.indices) {
                val tc = texCoordAccumulator[i]
                if (tc != null) {
                    buf.put(tc)
                } else {
                    repeat((positionAccumulator[i].size / 3) * 2) { buf.put(0f) }
                }
            }
        }
        val normalVbo = uploadFloatAttribute(3, 3, totalVertexCount * 3) { buf ->
            for (arr in normalAccumulator) buf.put(arr)
        }

        sharedIboId = glGenBuffers()
        val idxBuffer = MemoryUtil.memAllocInt(totalIndexCount)
        try {
            for (arr in indexAccumulator) idxBuffer.put(arr)
            idxBuffer.flip()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, sharedIboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, idxBuffer, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(idxBuffer)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        vboIds.clear()
        vboIds.addAll(listOf(posVbo, colorVbo, texVbo, normalVbo))

        clearGeometryAccumulators()

        val colorBytes =
            if (QUANTISE_COLORS) MeshBuffers.COLOR_BYTES else 3 * Float.SIZE_BYTES
        val vertexBytes = (3 + 2 + 3) * Float.SIZE_BYTES + colorBytes
        val geometryUploadMs = (System.nanoTime() - uploadStart) / 1e6
        geometryUploadCpuMs = (threadCpuNanos() - uploadCpuStart) / 1e6
        IndirectReuseStats.vertexBytes = vertexBytes
        sharedGeometryBytes =
            totalVertexCount.toLong() * vertexBytes + totalIndexCount.toLong() * Int.SIZE_BYTES
        println(
            "[IndirectRenderer] shared geometry: $totalVertexCount vertices x $vertexBytes B " +
                    "(colour ${if (QUANTISE_COLORS) "RGBA8" else "float32"}) + " +
                    "$totalIndexCount indices = ${sharedGeometryBytes / 1024} KiB in " +
                    "%.1f ms".format((System.nanoTime() - uploadStart) / 1e6)
        )

        buildTextureArrays()
        texturedBuckets = Array(arrayTextures.size) { TexturedBucket() }

        var drainMs = 0.0
        if (IndirectReuseStats.ENABLED) {
            val drainStart = System.nanoTime()
            glFinish()
            drainMs = (System.nanoTime() - drainStart) / 1e6
        }

        IndirectReuseStats.finishBuild(
            drainMs = drainMs,
            geometryUploadMs = geometryUploadMs,
            registerMs = registerAccumulateMs,
            texCpuMs = texArrayCpuMs,
            texUploadMs = texArrayUploadMs,
            texMipmapMs = texArrayMipmapMs,
            texGenMs = textureGenMs,
            registerCpuMs = registerAccumulateCpuMs,
            geometryUploadCpuMs = geometryUploadCpuMs,
            texArrayCpuMs = texArrayCpuTimeMs
        )

        geometryBuilt = true
    }

    var textureGenMs: Double = 0.0
        private set

    private val vboIds = mutableListOf<Int>()

    var sharedGeometryBytes: Long = 0L
        private set

    private fun uploadColorAttribute(location: Int, vertexCount: Int): Int {
        val vbo = glGenBuffers()
        val totalBytes = vertexCount * MeshBuffers.COLOR_BYTES
        val buffer = MemoryUtil.memAlloc(totalBytes)
        try {
            val baseAddress = MemoryUtil.memAddress(buffer)
            var vertexOffset = 0
            for (arr in colorAccumulator) {
                val vertices = arr.size / 3
                MeshBuffers.writeColorBytes(
                    baseAddress + vertexOffset.toLong() * MeshBuffers.COLOR_BYTES,
                    arr, vertices, 3
                )
                vertexOffset += vertices
            }
            buffer.position(0)
            buffer.limit(totalBytes)
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, 4, GL_UNSIGNED_BYTE, true, 0, 0)
            glVertexAttribDivisor(location, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        return vbo
    }

    private fun uploadFloatAttribute(
        location: Int,
        components: Int,
        floatCount: Int,
        fill: (FloatBuffer) -> Unit
    ): Int {
        val vbo = glGenBuffers()
        val buffer = MemoryUtil.memAllocFloat(floatCount)
        try {
            fill(buffer)
            buffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, components, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(location, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
        return vbo
    }

    private fun buildTextureArrays() {
        val provider = texturePixelProvider ?: return

        val materialIds = LinkedHashSet<Int>()
        for (entry in meshEntries) {
            if (entry.textureId >= 0) materialIds.add(entry.textureId)
        }
        if (materialIds.isEmpty()) return

        val genStart = System.nanoTime()
        val statsBefore = TexturePixelStats.snapshot()
        val bySize = LinkedHashMap<Int, MutableList<Pair<Int, IntArray>>>()
        val missing = mutableListOf<Int>()
        for (id in materialIds) {
            val pixelData = provider.getPixels(id)
            if (pixelData == null || pixelData.second != pixelData.third) {
                missing.add(id)
                continue
            }
            bySize.getOrPut(pixelData.second) { mutableListOf() }.add(id to pixelData.first)
        }
        textureGenMs = (System.nanoTime() - genStart) / 1e6
        println(
            "[IndirectRenderer] material pixels: %d in %.0f ms wall"
                .format(materialIds.size, textureGenMs)
        )
        println(TexturePixelStats.snapshot().since(statsBefore).describe("objectMaterials"))

        if (missing.isNotEmpty()) {
            missingTextureCount = missing.size
            val checkerSize = bySize.keys.maxOrNull() ?: 64
            val bucket = bySize.getOrPut(checkerSize) { mutableListOf() }
            val checker = checkerPixels(checkerSize)
            for (id in missing) bucket.add(id to checker)
            System.err.println("[IndirectRenderer] $missingTextureCount material(s) had no pixel data; using placeholder")
        }

        val maxLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS).coerceAtLeast(1)

        for ((size, entries) in bySize) {
            for (chunk in entries.chunked(maxLayers)) {
                val arrayIndex = arrayTextures.size
                val glId = createArrayTexture(size, chunk.map { it.second })
                arrayTextures.add(ArrayTexture(glId, size, chunk.size))
                for ((layer, pair) in chunk.withIndex()) {
                    materialSlots[pair.first] = (arrayIndex shl 16) or layer
                    IndirectReuseStats.recordMaterial(pair.first, size)
                }
            }
        }
        println(
            "[IndirectRenderer] array textures: " +
                    arrayTextures.joinToString { "${it.size}px x${it.layers}" }
        )

        assignMaterialSlots(0)

        if (AUDIT) {
            auditMaterials(materialSlots)
            auditLayerAlpha(bySize)
        }
    }

    private fun auditLayerAlpha(bySize: Map<Int, MutableList<Pair<Int, IntArray>>>) {
        val empty = mutableListOf<Int>()
        val sparse = mutableListOf<String>()
        for ((size, entries) in bySize) {
            for ((id, pixels) in entries) {
                var opaqueTexels = 0
                for (px in pixels) {
                    if (((px shr 24) and 0xFF) >= 3) opaqueTexels++
                }
                val fraction = opaqueTexels.toDouble() / pixels.size
                if (opaqueTexels == 0) empty.add(id)
                else if (fraction < 0.05) sparse.add("$id@${size}px=%.3f".format(fraction))
            }
        }
        println("[MDI-AUDIT] fully transparent materials: ${empty.size} $empty")
        println("[MDI-AUDIT] near transparent materials: ${sparse.size} $sparse")
    }

    private fun auditMaterials(slots: Map<Int, Int>) {
        var colorEntries = 0
        var texturedEntries = 0
        var colorTris = 0L
        var texturedTris = 0L
        val unresolved = LinkedHashSet<Int>()
        for (entry in meshEntries) {
            val tris = entry.indexCount / 3
            if (entry.textureId < 0) {
                colorEntries++
                colorTris += tris
                continue
            }
            if (entry.textureArrayIndex < 0) {
                unresolved.add(entry.textureId)
                colorEntries++
                colorTris += tris
            } else {
                texturedEntries++
                texturedTris += tris
            }
        }
        println(
            "[MDI-AUDIT] entries: colour=$colorEntries ($colorTris tris) " +
                    "textured=$texturedEntries ($texturedTris tris); " +
                    "materials=${slots.size} unresolved=${unresolved.size} " +
                    "${unresolved.take(16)}"
        )
    }

    private fun createArrayTexture(size: Int, layers: List<IntArray>): Int {
        val texCpuStart = threadCpuNanos()
        val texId = createEmptyArrayTexture(size, layers.size)
        uploadArrayLayers(size, 0, layers)
        applyArrayTextureState()
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        texArrayCpuTimeMs += (threadCpuNanos() - texCpuStart) / 1e6
        return texId
    }

    private fun createEmptyArrayTexture(size: Int, layerCount: Int): Int {
        val texId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D_ARRAY, texId)
        val allocStart = System.nanoTime()
        glTexImage3D(
            GL_TEXTURE_2D_ARRAY, 0, GL_RGBA,
            size, size, layerCount,
            0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?
        )
        texArrayUploadMs += (System.nanoTime() - allocStart) / 1e6
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        return texId
    }

    private fun uploadArrayLayers(size: Int, firstLayer: Int, layers: List<IntArray>) {
        if (layers.isEmpty()) return
        val buffer = MemoryUtil.memAlloc(size * size * 4)
        try {
            for ((layerIndex, pixels) in layers.withIndex()) {
                val flipStart = System.nanoTime()
                buffer.clear()
                for (y in (size - 1) downTo 0) {
                    val row = y * size
                    for (x in 0 until size) {
                        val pixel = pixels[row + x]
                        buffer.put(((pixel shr 16) and 0xFF).toByte())
                        buffer.put(((pixel shr 8) and 0xFF).toByte())
                        buffer.put((pixel and 0xFF).toByte())
                        buffer.put(((pixel shr 24) and 0xFF).toByte())
                    }
                }
                buffer.flip()
                texArrayCpuMs += (System.nanoTime() - flipStart) / 1e6
                val subStart = System.nanoTime()
                glTexSubImage3D(
                    GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, firstLayer + layerIndex,
                    size, size, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, buffer
                )
                texArrayUploadMs += (System.nanoTime() - subStart) / 1e6
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun applyArrayTextureState() {
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }
        val mipStart = System.nanoTime()
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        texArrayMipmapMs += (System.nanoTime() - mipStart) / 1e6
    }

    private fun checkerPixels(size: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val gray = if (((x / 8) + (y / 8)) % 2 == 0) 200 else 100
                pixels[y * size + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return pixels
    }


    fun resetFrameCounters() {
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0
        commandsThisFrame = 0
    }

    fun prepareFrame() {
        resetFrameCounters()
        droppedInstanceCount = 0
        colorCommandCount = 0
        texturedCommandCount = 0
        colorInstanceBytes = 0L
        texturedInstanceBytes = 0L
        for (list in colorInstanceData.values) list.clear()
        for (list in texturedInstanceData.values) list.clear()
        for (bucket in texturedBuckets) {
            bucket.firstCommand = 0
            bucket.commandCount = 0
        }
    }

    fun addInstanceRaw(modelId: Int, matrixData: FloatArray): Boolean {
        val batch = modelRegistry[modelId] ?: run {
            noteMissingModel(modelId, "addInstanceRaw")
            return false
        }

        for (entryIdx in batch.meshEntryIndices) {
            val entry = meshEntries[entryIdx]
            if (isTextured(entry)) {
                texturedInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            } else {
                colorInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            }
        }

        totalInstancesThisFrame++
        return true
    }

    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val matrixData = FloatArray(16)
        modelMatrix.get(matrixData)
        return addInstanceRaw(modelId, matrixData)
    }

    fun uploadAll() {
        if (!geometryBuilt) return
        uploadColorInstances()
        uploadTexturedInstances()
    }

    private fun ensureBuffer(
        buffer: PersistentBuffer?,
        currentCapacity: Int,
        neededUnits: Int,
        stride: Int,
        target: Int,
        label: String
    ): Pair<PersistentBuffer, Int> {
        require(neededUnits > 0) { "[IndirectRenderer] $label: asked to size for $neededUnits entries" }
        if (buffer != null && currentCapacity >= neededUnits) return buffer to currentCapacity
        buffer?.cleanup()
        val capacity = growCapacity(neededUnits)
        val fresh = PersistentBuffer(target, capacity.toLong() * stride)
        fresh.init()
        println(
            "[IndirectRenderer] $label ring sized for $capacity entries " +
                    "(${fresh.totalSize / 1024} KiB, ${if (fresh.isPersistent) "persistent" else "bufferSubData"})"
        )
        return fresh to capacity
    }

    private fun uploadColorInstances() {
        var instanceCount = 0
        var commandCount = 0
        for (instances in colorInstanceData.values) {
            if (instances.isEmpty()) continue
            instanceCount += instances.size
            commandCount++
        }
        if (instanceCount == 0) return

        val ssboPair = ensureBuffer(colorSsbo, colorInstanceCapacity, instanceCount, COLOR_INSTANCE_STRIDE, GL_SHADER_STORAGE_BUFFER, "color instance")
        colorSsbo = ssboPair.first
        colorInstanceCapacity = ssboPair.second
        val cmdPair = ensureBuffer(colorIndirectBuffer, colorCommandCapacity, commandCount, INDIRECT_COMMAND_SIZE, GL_DRAW_INDIRECT_BUFFER, "color command")
        colorIndirectBuffer = cmdPair.first
        colorCommandCapacity = cmdPair.second

        val instanceBuf = colorSsbo!!.beginFrame()
        val cmdBuf = colorIndirectBuffer!!.beginFrame()

        var instanceOffset = 0
        var written = 0

        for (entryIdx in colorInstanceData.keys.sortedBy { batchOrderKey(it) }) {
            val instances = colorInstanceData[entryIdx] ?: continue
            if (instances.isEmpty()) continue

            val entry = meshEntries[entryIdx]

            for (matrix in instances) {
                for (f in matrix) instanceBuf.putFloat(f)
                instanceBuf.putInt(-1)
                instanceBuf.putInt(0)
                instanceBuf.putInt(0)
                instanceBuf.putInt(0)
            }

            writeCommand(cmdBuf, entry.indexCount, instances.size, entry.firstIndex, entry.baseVertex, instanceOffset)

            instanceOffset += instances.size
            written++
        }

        colorInstanceBytes = instanceOffset.toLong() * COLOR_INSTANCE_STRIDE
        colorCommandCount = written

        colorSsbo!!.endWrite(colorInstanceBytes)
        colorIndirectBuffer!!.endWrite(written.toLong() * INDIRECT_COMMAND_SIZE)
    }

    private fun uploadTexturedInstances() {
        if (arrayTextures.isEmpty()) return

        var instanceCount = 0
        var commandCount = 0
        for (instances in texturedInstanceData.values) {
            if (instances.isEmpty()) continue
            instanceCount += instances.size
            commandCount++
        }
        if (instanceCount == 0) return

        val ssboPair = ensureBuffer(texturedSsbo, texturedInstanceCapacity, instanceCount, TEXTURED_INSTANCE_STRIDE, GL_SHADER_STORAGE_BUFFER, "textured instance")
        texturedSsbo = ssboPair.first
        texturedInstanceCapacity = ssboPair.second
        val cmdPair = ensureBuffer(texturedIndirectBuffer, texturedCommandCapacity, commandCount, INDIRECT_COMMAND_SIZE, GL_DRAW_INDIRECT_BUFFER, "textured command")
        texturedIndirectBuffer = cmdPair.first
        texturedCommandCapacity = cmdPair.second

        val instanceBuf = texturedSsbo!!.beginFrame()
        val cmdBuf = texturedIndirectBuffer!!.beginFrame()

        val sortedEntries = texturedCommandOrder(
            texturedInstanceData.keys.filter { texturedInstanceData[it]?.isNotEmpty() == true }
        )

        var instanceOffset = 0
        var written = 0
        var currentArray = -1

        for (entryIdx in sortedEntries) {
            val instances = texturedInstanceData[entryIdx] ?: continue
            val entry = meshEntries[entryIdx]

            if (!singleTexturedDraw) {
                if (entry.textureArrayIndex != currentArray) {
                    currentArray = entry.textureArrayIndex
                    texturedBuckets[currentArray].firstCommand = written
                }
                texturedBuckets[currentArray].commandCount++
            }

            val layer = entry.textureLayer
            val arraySlot = instanceArraySlot(entry)
            for (matrix in instances) {
                for (f in matrix) instanceBuf.putFloat(f)
                instanceBuf.putInt(layer)
                instanceBuf.putInt(arraySlot)
                instanceBuf.putInt(0)
                instanceBuf.putInt(0)
            }

            writeCommand(cmdBuf, entry.indexCount, instances.size, entry.firstIndex, entry.baseVertex, instanceOffset)

            instanceOffset += instances.size
            written++
        }

        texturedInstanceBytes = instanceOffset.toLong() * TEXTURED_INSTANCE_STRIDE
        texturedCommandCount = written

        texturedSsbo!!.endWrite(texturedInstanceBytes)
        texturedIndirectBuffer!!.endWrite(written.toLong() * INDIRECT_COMMAND_SIZE)
    }

    private fun writeCommand(
        buf: ByteBuffer,
        count: Int,
        instanceCount: Int,
        firstIndex: Int,
        baseVertex: Int,
        baseInstance: Int
    ) {
        buf.putInt(count)
        buf.putInt(instanceCount)
        buf.putInt(firstIndex)
        buf.putInt(baseVertex)
        buf.putInt(baseInstance)
    }

    fun renderUntextured(shader: ShaderProgram) {
        if (!geometryBuilt) return
        if (staticSceneBuilt) {
            renderStaticUntextured(shader)
            return
        }
        if (colorCommandCount == 0) return

        val ssbo = colorSsbo ?: return
        val indirect = colorIndirectBuffer ?: return

        shader.bind()
        ssbo.bindRange(INSTANCE_SSBO_BINDING, colorInstanceBytes)
        bindStreamingIdentity()

        glBindVertexArray(sharedVaoId)
        indirect.bind()

        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, indirect.readOffset(), colorCommandCount, 0)

        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)

        drawCallsThisFrame++
        commandsThisFrame += colorCommandCount
    }

    fun renderTextured(shader: ShaderProgram) {
        if (!geometryBuilt) return
        if (staticSceneBuilt) {
            renderStaticTextured(shader)
            return
        }
        if (texturedCommandCount == 0) return

        val ssbo = texturedSsbo ?: return
        val indirect = texturedIndirectBuffer ?: return

        shader.bind()
        ssbo.bindRange(INSTANCE_SSBO_BINDING, texturedInstanceBytes)
        bindStreamingIdentity()

        glBindVertexArray(sharedVaoId)
        indirect.bind()
        bindArrayTextures(shader)

        val baseOffset = indirect.readOffset()
        if (singleTexturedDraw) {
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, baseOffset, texturedCommandCount, 0)
            drawCallsThisFrame++
            commandsThisFrame += texturedCommandCount
        } else {
            for ((arrayIndex, bucket) in texturedBuckets.withIndex()) {
                if (bucket.commandCount == 0) continue
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[arrayIndex].glId)
                shader.setUniform("textureArrays[0]", 0)
                val offset = baseOffset + bucket.firstCommand.toLong() * INDIRECT_COMMAND_SIZE
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, bucket.commandCount, 0)
                drawCallsThisFrame++
                commandsThisFrame += bucket.commandCount
            }
        }

        unbindArrayTextures()
        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
    }

    fun endFrame() {
        colorSsbo?.endFrame()
        colorIndirectBuffer?.endFrame()
        texturedSsbo?.endFrame()
        texturedIndirectBuffer?.endFrame()
    }


    private class StaticScene {
        var itemCount = 0
        var commandCount = 0

        var colorCommandCount = 0
        var triangleCount = 0

        var instanceSsbo = 0
        var boundsSsbo = 0
        var itemMetaSsbo = 0
        var visibleSsbo = 0
        var commandBuffer = 0

        var buckets = emptyArray<TexturedBucket>()

        fun cleanup() {
            for (id in intArrayOf(instanceSsbo, boundsSsbo, itemMetaSsbo, visibleSsbo, commandBuffer)) {
                if (id != 0) glDeleteBuffers(id)
            }
            instanceSsbo = 0
            boundsSsbo = 0
            itemMetaSsbo = 0
            visibleSsbo = 0
            commandBuffer = 0
            itemCount = 0
            commandCount = 0
            colorCommandCount = 0
            triangleCount = 0
            buckets = emptyArray()
        }
    }

    private val staticScene = StaticScene()

    var staticSceneBuilt: Boolean = false
        private set

    var cullingActive: Boolean = false
        private set

    private var cullProgram: ShaderProgram? = null
    private var cullResetProgram: ShaderProgram? = null

    private val pendingModelIds = mutableListOf<Int>()
    private val pendingMatrices = mutableListOf<FloatArray>()

    private var streamingIdentityBuffer = 0
    private var streamingIdentityCapacity = 0

    private var statsDeviceBuffer = 0
    private var statsReadbackBuffer = 0
    private var statsMap: ByteBuffer? = null
    private var statsSlot = 0
    private var statsFramesDispatched = 0

    private var pendingStatsSlot = -1
    private var pendingStatsFence = 0L
    private var framesSinceStatsCopy = 0

    var visibleInstancesStale: Int = -1
        private set
    var visibleTrianglesStale: Int = -1
        private set
    var statsAgeFrames: Int = 0
        private set

    private var staticInstanceCount = 0

    val staticItemCount: Int get() = staticScene.itemCount
    val staticTriangleCount: Int get() = staticScene.triangleCount

    fun initCulling(cullSource: String, resetSource: String): Boolean {
        if (!isComputeSupported()) {
            println("[IndirectRenderer] compute shaders unavailable; GPU culling disabled")
            return false
        }
        return try {
            val cullStart = System.nanoTime()
            cullProgram = ShaderProgram.createCompute(cullSource, "frustumPlanes", "itemCount", "statsIndex")
            cullResetProgram = ShaderProgram.createCompute(resetSource, "commandCount", "statsIndex")
            val compileMs = (System.nanoTime() - cullStart) / 1e6
            createStatsBuffers()
            cullingActive = true
            println(
                "[IndirectRenderer] GPU frustum culling enabled (counter readback: " +
                        "${if (statsMap != null) "mapped ring" else "unavailable"}), " +
                        "compute programs compiled in %.1f ms".format(compileMs)
            )
            true
        } catch (e: Exception) {
            System.err.println("[IndirectRenderer] cull shader build failed, drawing unculled: $e")
            cullProgram?.cleanup()
            cullProgram = null
            cullResetProgram?.cleanup()
            cullResetProgram = null
            cullingActive = false
            false
        }
    }

    fun beginStaticScene() {
        pendingModelIds.clear()
        pendingMatrices.clear()
        droppedInstanceCount = 0
    }

    fun addStaticInstance(modelId: Int, matrixData: FloatArray): Boolean {
        if (!modelRegistry.containsKey(modelId)) {
            noteMissingModel(modelId, "addStaticInstance")
            return false
        }
        pendingModelIds.add(modelId)
        pendingMatrices.add(matrixData)
        return true
    }

    fun buildStaticScene() {
        val sceneStart = System.nanoTime()
        releaseStaticScene()
        if (!geometryBuilt || pendingModelIds.isEmpty()) return

        val colorItems = LinkedHashMap<Int, MutableList<Int>>()
        val texturedItems = LinkedHashMap<Int, MutableList<Int>>()

        for (instanceIndex in pendingModelIds.indices) {
            val modelId = pendingModelIds[instanceIndex]
            val batch = modelRegistry[modelId] ?: run {
                noteMissingModel(modelId, "buildStaticScene")
                null
            } ?: continue
            for (entryIdx in batch.meshEntryIndices) {
                val target = if (isTextured(meshEntries[entryIdx])) texturedItems else colorItems
                target.getOrPut(entryIdx) { mutableListOf() }.add(instanceIndex)
            }
        }

        val texturedOrder = texturedCommandOrder(texturedItems.keys)
        val commandOrder = colorItems.keys.sortedBy { batchOrderKey(it) } +
                (if (REVERSE_COMMANDS) texturedOrder.reversed() else texturedOrder)
        val allItems = LinkedHashMap<Int, MutableList<Int>>(colorItems)
        allItems.putAll(texturedItems)

        buildScene(allItems, commandOrder, colorItems.size)

        staticSceneBuilt = staticScene.itemCount > 0
        staticInstanceCount = pendingModelIds.size
        pendingModelIds.clear()
        pendingMatrices.clear()

        println(
            "[IndirectRenderer] static scene: ${staticScene.itemCount} draw items in " +
                    "${staticScene.commandCount} commands (${staticScene.colorCommandCount} colour), " +
                    "${staticScene.triangleCount} triangles, ${staticSceneBytes() / 1024} KiB in " +
                    "%.1f ms".format((System.nanoTime() - sceneStart) / 1e6)
        )
    }

    private fun buildScene(
        items: Map<Int, MutableList<Int>>,
        commandOrder: List<Int>,
        colorCommandCount: Int
    ) {
        val itemCount = items.values.sumOf { it.size }
        if (itemCount == 0) return

        val scene = staticScene
        val commandCount = commandOrder.size
        scene.itemCount = itemCount
        scene.commandCount = commandCount
        scene.colorCommandCount = colorCommandCount

        val commands = IntArray(commandCount * 5)
        val bounds = FloatArray(itemCount * 4)
        val itemMeta = IntArray(itemCount * 2)
        val identity = IntArray(itemCount) { it }
        val instanceData = MemoryUtil.memAlloc(itemCount * INSTANCE_STRIDE).order(ByteOrder.nativeOrder())

        val buckets = Array(arrayTextures.size) { TexturedBucket() }

        var itemIndex = 0
        var triangles = 0
        try {
            for ((commandIndex, entryIdx) in commandOrder.withIndex()) {
                val entry = meshEntries[entryIdx]
                val instances = items[entryIdx] ?: continue
                val trianglesPerItem = entry.indexCount / 3

                if (!singleTexturedDraw && isTextured(entry)) {
                    val bucket = buckets[entry.textureArrayIndex]
                    if (bucket.commandCount == 0) bucket.firstCommand = commandIndex
                    bucket.commandCount++
                }

                val base = commandIndex * 5
                commands[base] = entry.indexCount
                commands[base + 1] = 0
                commands[base + 2] = entry.firstIndex
                commands[base + 3] = entry.baseVertex
                commands[base + 4] = itemIndex

                for (instanceIdx in instances) {
                    val matrix = pendingMatrices[instanceIdx]
                    for (f in matrix) instanceData.putFloat(f)
                    instanceData.putInt(entry.textureLayer)
                    instanceData.putInt(instanceArraySlot(entry))
                    instanceData.putInt(0)
                    instanceData.putInt(0)

                    writeWorldSphere(entry.localBounds, matrix, bounds, itemIndex * 4)
                    itemMeta[itemIndex * 2] = commandIndex
                    itemMeta[itemIndex * 2 + 1] = trianglesPerItem
                    triangles += trianglesPerItem
                    itemIndex++
                }
            }
            instanceData.flip()

            scene.triangleCount = triangles
            scene.instanceSsbo = createBuffer(GL_SHADER_STORAGE_BUFFER, instanceData, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(instanceData)
        }

        scene.boundsSsbo = createFloatBuffer(GL_SHADER_STORAGE_BUFFER, bounds, GL_STATIC_DRAW)
        scene.itemMetaSsbo = createIntBuffer(GL_SHADER_STORAGE_BUFFER, itemMeta, GL_STATIC_DRAW)
        scene.visibleSsbo = createIntBuffer(GL_SHADER_STORAGE_BUFFER, identity, GL_DYNAMIC_DRAW)

        if (!cullingActive) {
            for ((commandIndex, entryIdx) in commandOrder.withIndex()) {
                commands[commandIndex * 5 + 1] = items[entryIdx]?.size ?: 0
            }
        }
        scene.commandBuffer = createIntBuffer(GL_DRAW_INDIRECT_BUFFER, commands, GL_DYNAMIC_DRAW)
        scene.buckets = buckets
    }

    private fun writeWorldSphere(local: FloatArray, m: FloatArray, out: FloatArray, offset: Int) {
        if (local.isEmpty()) {
            out[offset] = 0f
            out[offset + 1] = 0f
            out[offset + 2] = 0f
            out[offset + 3] = Float.MAX_VALUE
            return
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        for (corner in 0 until 8) {
            val lx = if ((corner and 1) == 0) local[0] else local[3]
            val ly = if ((corner and 2) == 0) local[1] else local[4]
            val lz = if ((corner and 4) == 0) local[2] else local[5]

            val wx = m[0] * lx + m[4] * ly + m[8] * lz + m[12]
            val wy = m[1] * lx + m[5] * ly + m[9] * lz + m[13]
            val wz = m[2] * lx + m[6] * ly + m[10] * lz + m[14]

            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wz < minZ) minZ = wz
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
            if (wz > maxZ) maxZ = wz
        }

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val hx = maxX - cx
        val hy = maxY - cy
        val hz = maxZ - cz

        out[offset] = cx
        out[offset + 1] = cy
        out[offset + 2] = cz
        out[offset + 3] = sqrt(hx * hx + hy * hy + hz * hz) * BOUND_SLACK
    }

    fun cullFrame(planes: FloatArray) {
        if (!staticSceneBuilt || !cullingActive) return
        val cull = cullProgram ?: return
        val reset = cullResetProgram ?: return
        val scene = staticScene

        publishAndSampleStats()

        val statsIndex = statsSlot * STATS_UINTS_PER_SLOT

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_STATS_BINDING, statsDeviceBuffer)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_COMMAND_BINDING, scene.commandBuffer)

        reset.bind()
        reset.setUniformUInt("commandCount", scene.commandCount)
        reset.setUniformUInt("statsIndex", statsIndex)
        glDispatchCompute(dispatchGroups(scene.commandCount), 1, 1)

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_BOUNDS_BINDING, scene.boundsSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, CULL_ITEM_META_BINDING, scene.itemMetaSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        cull.bind()
        cull.setUniformVec4Array("frustumPlanes", planes)
        cull.setUniformUInt("itemCount", scene.itemCount)
        cull.setUniformUInt("statsIndex", statsIndex)
        glDispatchCompute(dispatchGroups(scene.itemCount), 1, 1)

        for (binding in intArrayOf(
            CULL_COMMAND_BINDING, CULL_STATS_BINDING, CULL_BOUNDS_BINDING, CULL_ITEM_META_BINDING
        )) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, 0)
        }

        statsSlot = (statsSlot + 1) % STATS_SLOTS
        statsFramesDispatched++
    }

    fun cullBarrier() {
        if (!staticSceneBuilt || !cullingActive) return
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
    }

    private fun renderStaticUntextured(shader: ShaderProgram) {
        val scene = staticScene
        if (scene.colorCommandCount == 0) return

        shader.bind()
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, scene.instanceSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        glBindVertexArray(sharedVaoId)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, scene.commandBuffer)
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, scene.colorCommandCount, 0)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)
        glBindVertexArray(0)

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, 0)

        drawCallsThisFrame++
        commandsThisFrame += scene.colorCommandCount
    }

    private fun renderStaticTextured(shader: ShaderProgram) {
        val scene = staticScene
        val texturedCommands = scene.commandCount - scene.colorCommandCount
        if (texturedCommands == 0) return

        shader.bind()
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, scene.instanceSsbo)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, scene.visibleSsbo)

        glBindVertexArray(sharedVaoId)
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, scene.commandBuffer)
        bindArrayTextures(shader)

        if (singleTexturedDraw) {
            val offset = scene.colorCommandCount.toLong() * INDIRECT_COMMAND_SIZE
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, texturedCommands, 0)
            drawCallsThisFrame++
            commandsThisFrame += texturedCommands
        } else {
            for ((arrayIndex, bucket) in scene.buckets.withIndex()) {
                if (bucket.commandCount == 0) continue
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTextures[arrayIndex].glId)
                shader.setUniform("textureArrays[0]", 0)
                val offset = bucket.firstCommand.toLong() * INDIRECT_COMMAND_SIZE
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, bucket.commandCount, 0)
                drawCallsThisFrame++
                commandsThisFrame += bucket.commandCount
            }
        }

        unbindArrayTextures()
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, 0)
    }

    private fun bindStreamingIdentity() {
        val needed = maxOf(colorInstanceCapacity, texturedInstanceCapacity)
        if (needed > streamingIdentityCapacity) {
            if (streamingIdentityBuffer != 0) glDeleteBuffers(streamingIdentityBuffer)
            streamingIdentityBuffer = createIntBuffer(
                GL_SHADER_STORAGE_BUFFER, IntArray(needed) { it }, GL_STATIC_DRAW
            )
            streamingIdentityCapacity = needed
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, streamingIdentityBuffer)
    }

    private fun createStatsBuffers() {
        val bytes = (STATS_SLOTS * STATS_UINTS_PER_SLOT * 4).toLong()
        statsDeviceBuffer = createIntBuffer(
            GL_SHADER_STORAGE_BUFFER, IntArray(STATS_SLOTS * STATS_UINTS_PER_SLOT), GL_DYNAMIC_COPY
        )

        val caps = GL.getCapabilities()
        if (!caps.OpenGL44 && !caps.GL_ARB_buffer_storage) {
            return
        }
        statsReadbackBuffer = glGenBuffers()
        glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
        val flags = GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT
        glBufferStorage(GL_COPY_WRITE_BUFFER, bytes, flags)
        val mapped = glMapBufferRange(GL_COPY_WRITE_BUFFER, 0, bytes, flags)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
        statsMap = mapped?.order(ByteOrder.nativeOrder())
    }

    private fun publishAndSampleStats() {
        if (statsMap == null || statsReadbackBuffer == 0) return

        val wanted = STATS_FORCED || statsRequestFramesLeft > 0
        if (statsRequestFramesLeft > 0) statsRequestFramesLeft--
        if (!wanted) {
            if (pendingStatsFence != 0L) {
                glDeleteSync(pendingStatsFence)
                pendingStatsFence = 0L
                pendingStatsSlot = -1
            }
            visibleInstancesStale = -1
            visibleTrianglesStale = -1
            framesSinceStatsCopy = 0
            return
        }

        framesSinceStatsCopy++

        if (pendingStatsFence != 0L) {
            val result = glClientWaitSync(pendingStatsFence, 0, 0L)
            if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) return

            glDeleteSync(pendingStatsFence)
            pendingStatsFence = 0L

            val map = statsMap!!
            val base = pendingStatsSlot * STATS_UINTS_PER_SLOT * 4
            visibleInstancesStale = map.getInt(base)
            visibleTrianglesStale = map.getInt(base + 4)
            statsAgeFrames = framesSinceStatsCopy + 1
            return
        }

        if (statsFramesDispatched == 0 || framesSinceStatsCopy < STATS_SAMPLE_INTERVAL) return

        val previous = (statsSlot + STATS_SLOTS - 1) % STATS_SLOTS
        val offset = (previous * STATS_UINTS_PER_SLOT * 4).toLong()
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_COPY_READ_BUFFER, statsDeviceBuffer)
        glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
        glCopyBufferSubData(
            GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER,
            offset, offset, (STATS_UINTS_PER_SLOT * 4).toLong()
        )
        glBindBuffer(GL_COPY_READ_BUFFER, 0)
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0)

        pendingStatsSlot = previous
        pendingStatsFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        framesSinceStatsCopy = 0
    }

    fun staticSceneBytes(): Long {
        val scene = staticScene
        return scene.itemCount.toLong() * (INSTANCE_STRIDE + 16 + 8 + 4) +
                scene.commandCount.toLong() * INDIRECT_COMMAND_SIZE
    }

    private fun releaseStaticScene() {
        staticScene.cleanup()
        staticSceneBuilt = false
        visibleInstancesStale = -1
        visibleTrianglesStale = -1
        statsFramesDispatched = 0
        if (pendingStatsFence != 0L) {
            glDeleteSync(pendingStatsFence)
            pendingStatsFence = 0L
        }
        pendingStatsSlot = -1
        framesSinceStatsCopy = 0
        statsSlot = 0
    }

    fun getDrawCallCount(): Int = drawCallsThisFrame

    fun getCommandCount(): Int = commandsThisFrame

    fun getTotalInstances(): Int =
        if (staticSceneBuilt) staticInstanceCount else totalInstancesThisFrame

    fun getTotalTrianglesSubmitted(): Int {
        if (staticSceneBuilt) return staticTriangleCount
        var total = 0
        for ((entryIdx, instances) in colorInstanceData) {
            if (instances.isNotEmpty()) total += (meshEntries[entryIdx].indexCount / 3) * instances.size
        }
        for ((entryIdx, instances) in texturedInstanceData) {
            if (instances.isNotEmpty()) total += (meshEntries[entryIdx].indexCount / 3) * instances.size
        }
        return total
    }

    fun getRegisteredModelCount(): Int = modelRegistry.size

    fun getMeshEntryCount(): Int = meshEntries.size

    fun getTextureArrayCount(): Int = arrayTextures.size

    fun getTextureLayerCount(): Int = arrayTextures.sumOf { it.layers }

    fun isReady(): Boolean = initialized && geometryBuilt


    fun cleanup() {
        releaseStaticScene()
        cullProgram?.cleanup()
        cullProgram = null
        cullResetProgram?.cleanup()
        cullResetProgram = null
        cullingActive = false
        if (statsReadbackBuffer != 0) {
            if (statsMap != null) {
                glBindBuffer(GL_COPY_WRITE_BUFFER, statsReadbackBuffer)
                glUnmapBuffer(GL_COPY_WRITE_BUFFER)
                glBindBuffer(GL_COPY_WRITE_BUFFER, 0)
                statsMap = null
            }
            glDeleteBuffers(statsReadbackBuffer)
            statsReadbackBuffer = 0
        }
        if (statsDeviceBuffer != 0) {
            glDeleteBuffers(statsDeviceBuffer)
            statsDeviceBuffer = 0
        }
        if (streamingIdentityBuffer != 0) {
            glDeleteBuffers(streamingIdentityBuffer)
            streamingIdentityBuffer = 0
            streamingIdentityCapacity = 0
        }
        pendingModelIds.clear()
        pendingMatrices.clear()

        colorSsbo?.cleanup()
        colorSsbo = null
        texturedSsbo?.cleanup()
        texturedSsbo = null
        colorIndirectBuffer?.cleanup()
        colorIndirectBuffer = null
        texturedIndirectBuffer?.cleanup()
        texturedIndirectBuffer = null
        colorInstanceCapacity = 0
        texturedInstanceCapacity = 0
        colorCommandCapacity = 0
        texturedCommandCapacity = 0

        for (vbo in vboIds) glDeleteBuffers(vbo)
        vboIds.clear()

        if (sharedIboId != 0) {
            glDeleteBuffers(sharedIboId)
            sharedIboId = 0
        }
        if (sharedVaoId != 0) {
            glDeleteVertexArrays(sharedVaoId)
            sharedVaoId = 0
        }

        for (tex in arrayTextures) glDeleteTextures(tex.glId)
        arrayTextures.clear()
        materialSlots.clear()
        texturedBuckets = emptyArray()

        meshEntries.clear()
        modelRegistry.clear()
        droppedModelIds.clear()
        droppedInstanceCount = 0
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()
        colorInstanceData.clear()
        texturedInstanceData.clear()

        totalVertexCount = 0
        totalIndexCount = 0
        colorCommandCount = 0
        texturedCommandCount = 0
        colorInstanceBytes = 0L
        texturedInstanceBytes = 0L
        missingTextureCount = 0
        initialized = false
        geometryBuilt = false
    }
}
