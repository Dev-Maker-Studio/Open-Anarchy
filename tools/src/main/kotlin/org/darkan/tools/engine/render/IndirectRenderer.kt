package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL12.glTexSubImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL33.glVertexAttribDivisor
import org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Multi-Draw Indirect renderer that packs all registered model geometries into
 * a single shared VBO/IBO and issues 1-3 glMultiDrawElementsIndirect calls per frame.
 *
 * Architecture:
 * - All mesh vertex data is packed into a single shared VBO at registration time.
 * - All mesh index data is packed into a single shared IBO with baseVertex offsets.
 * - Per-frame instance data (model matrices + optional texture layer) is uploaded to an SSBO.
 * - DrawElementsIndirectCommand structs are built per frame and uploaded to GL_DRAW_INDIRECT_BUFFER.
 * - Textured objects use a sampler2DArray built from the object atlas pages.
 *
 * This collapses ~50-150 draw calls down to 1-3:
 *   1 for untextured objects, 1 for textured objects (atlas), potentially 1 for fallback textures.
 *
 * Requires OpenGL 4.3+ (SSBO, glMultiDrawElementsIndirect).
 */
class IndirectRenderer(
    /** Maximum total instances across all meshes per frame. */
    private val maxTotalInstances: Int = 200000,
    /** Object texture atlas (same one used by InstanceManager). */
    private val objectAtlas: TextureAtlas? = null,
    /** Provider for texture pixel data, required when objectAtlas is set. */
    private val texturePixelProvider: TexturePixelProvider? = null
) {
    // ====================================================================
    // Shared geometry buffer
    // ====================================================================

    /** VAO for the shared geometry. */
    private var sharedVaoId: Int = 0

    /** Shared VBO for all vertex data (position, color, texCoord, normal interleaved). */
    private var sharedVboId: Int = 0

    /** Shared IBO for all index data. */
    private var sharedIboId: Int = 0

    /** Current total vertex count in the shared VBO. */
    private var totalVertexCount: Int = 0

    /** Current total index count in the shared IBO. */
    private var totalIndexCount: Int = 0

    // Accumulators for vertex/index data during registration phase.
    // Cleared after buildSharedGeometry() uploads to GPU.
    private val positionAccumulator = mutableListOf<FloatArray>()
    private val colorAccumulator = mutableListOf<FloatArray>()
    private val texCoordAccumulator = mutableListOf<FloatArray?>()
    private val normalAccumulator = mutableListOf<FloatArray>()
    private val indexAccumulator = mutableListOf<IntArray>()

    // ====================================================================
    // Per-mesh metadata (registered at model registration time)
    // ====================================================================

    /**
     * Metadata for a single mesh batch within the shared geometry buffer.
     * Stores the offsets needed for DrawElementsIndirectCommand.
     */
    private class MeshEntry(
        /** Number of indices for this mesh. */
        val indexCount: Int,
        /** Offset (in indices) into the shared IBO. */
        val firstIndex: Int,
        /** Offset (in vertices) into the shared VBO. */
        val baseVertex: Int,
        /** True if this mesh has texture coordinates. */
        val hasTexCoords: Boolean,
        /**
         * Atlas page index for textured meshes, or -1 for untextured.
         * -2 or lower indicates a fallback texture (not in atlas).
         */
        val atlasPageIndex: Int,
        /**
         * Original texture ID for fallback textures (not packed in atlas).
         * Only valid when atlasPageIndex < -1.
         */
        val fallbackTextureId: Int
    )

    /** Resolved batch for a model, referencing one or more MeshEntry objects. */
    private class ResolvedModelBatch(val meshEntryIndices: List<Int>)

    /** All registered mesh entries. Index into this list is the meshEntryId. */
    private val meshEntries = mutableListOf<MeshEntry>()

    /** Model ID -> list of mesh entry indices. */
    private val modelRegistry = mutableMapOf<Int, ResolvedModelBatch>()

    // ====================================================================
    // Persistent mapped buffers for dynamic per-frame data
    // ====================================================================

    /** Persistent SSBO for untextured instance matrices (mat4 only). */
    private var colorSsbo: PersistentBuffer? = null

    /** Persistent SSBO for textured instance data (mat4 + textureLayer). */
    private var texturedSsbo: PersistentBuffer? = null

    /** Persistent GL_DRAW_INDIRECT_BUFFER for untextured commands. */
    private var colorIndirectBuffer: PersistentBuffer? = null

    /** Persistent GL_DRAW_INDIRECT_BUFFER for textured commands. */
    private var texturedIndirectBuffer: PersistentBuffer? = null

    // ====================================================================
    // Texture array
    // ====================================================================

    /** GL texture ID for the sampler2DArray built from atlas pages. */
    private var textureArrayId: Int = 0

    /** Number of layers in the texture array. */
    private var textureArrayLayers: Int = 0

    // ====================================================================
    // Per-frame state
    // ====================================================================

    /** Per-mesh instance counts for the current frame. */
    private val meshInstanceCounts = mutableMapOf<Int, Int>()

    // Per-mesh instance data offsets (baseInstance for each mesh entry)
    private val colorBaseInstances = mutableMapOf<Int, Int>()
    private val texturedBaseInstances = mutableMapOf<Int, Int>()

    // Frame instance data accumulators (mesh entry id -> list of raw matrix floats)
    private val colorInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()
    private val texturedInstanceData = mutableMapOf<Int, MutableList<FloatArray>>()

    // Stats
    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0
    private var initialized = false
    private var geometryBuilt = false

    // ====================================================================
    // Constants
    // ====================================================================

    companion object {
        /** Size of DrawElementsIndirectCommand in bytes: 5 uints * 4 bytes = 20 bytes. */
        private const val INDIRECT_COMMAND_SIZE = 20

        /** Size of untextured instance data: mat4 = 16 floats * 4 bytes = 64 bytes. */
        private const val COLOR_INSTANCE_STRIDE = 64

        /** Size of textured instance data: mat4 (64 bytes) + ivec4 (16 bytes) = 80 bytes. */
        private const val TEXTURED_INSTANCE_STRIDE = 80

        /** SSBO binding point for instance data. */
        private const val INSTANCE_SSBO_BINDING = 1

        /** Max indirect commands per draw call. */
        private const val MAX_COMMANDS = 8192
    }

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Initialize GL resources for the indirect renderer.
     * Call once during setup after OpenGL context is available.
     *
     * Creates four [PersistentBuffer] instances (triple-buffered persistent mapped if GL 4.4+
     * is available, otherwise falls back to glBufferSubData):
     * - Color instance SSBO (untextured matrices)
     * - Textured instance SSBO (matrices + texture layer)
     * - Color indirect command buffer
     * - Textured indirect command buffer
     */
    fun init() {
        // Create shared VAO
        sharedVaoId = glGenVertexArrays()

        // Create persistent mapped buffers for dynamic per-frame data.
        // Each PersistentBuffer internally handles triple buffering and fence sync,
        // or falls back to glBufferSubData if persistent mapping is unavailable.

        val colorSsboSection = (maxTotalInstances * COLOR_INSTANCE_STRIDE).toLong()
        colorSsbo = PersistentBuffer(GL_SHADER_STORAGE_BUFFER, colorSsboSection).also { it.init() }

        val texturedSsboSection = (maxTotalInstances * TEXTURED_INSTANCE_STRIDE).toLong()
        texturedSsbo = PersistentBuffer(GL_SHADER_STORAGE_BUFFER, texturedSsboSection).also { it.init() }

        val indirectSection = (MAX_COMMANDS * INDIRECT_COMMAND_SIZE).toLong()
        colorIndirectBuffer = PersistentBuffer(GL_DRAW_INDIRECT_BUFFER, indirectSection).also { it.init() }
        texturedIndirectBuffer = PersistentBuffer(GL_DRAW_INDIRECT_BUFFER, indirectSection).also { it.init() }

        val mode = if (colorSsbo!!.isPersistent) "persistent mapped (triple-buffered)" else "fallback (glBufferSubData)"
        println("[IndirectRenderer] Dynamic buffers initialized: $mode")

        initialized = true
    }

    // ====================================================================
    // Model registration
    // ====================================================================

    /**
     * Register mesh data for a model ID, appending its geometry to the shared buffers.
     *
     * When [objectAtlas] is configured, textured MeshData entries are resolved to
     * their atlas page index. UV coordinates are remapped to atlas space at registration time.
     *
     * @param modelId The model identifier
     * @param meshDataList List of mesh data (one per texture batch)
     */
    fun registerModel(modelId: Int, meshDataList: List<MeshData>) {
        if (modelRegistry.containsKey(modelId)) return

        val entryIndices = mutableListOf<Int>()
        val atlas = objectAtlas
        val pixelProvider = texturePixelProvider

        for (meshData in meshDataList) {
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) continue

            val numVertices = meshData.positions.size / 3

            // Determine atlas page index and remap UVs if applicable
            var atlasPageIndex = -1
            var fallbackTextureId = -1
            var texCoords = meshData.textureCoords
            val hasTexCoords = texCoords != null && meshData.textureId >= 0

            if (hasTexCoords && atlas != null && pixelProvider != null && meshData.textureId >= 0) {
                // Try to get or pack texture into atlas
                var region = atlas.getRegion(meshData.textureId)
                if (region == null) {
                    val pixelData = pixelProvider.getPixels(meshData.textureId)
                    if (pixelData != null) {
                        region = atlas.addTexture(meshData.textureId, pixelData.first, pixelData.second, pixelData.third)
                    }
                }

                if (region != null) {
                    atlasPageIndex = region.atlasPageIndex
                    // Remap UVs to atlas space
                    texCoords = remapUVsToAtlas(texCoords, region)
                } else {
                    // Fallback: texture couldn't be packed
                    fallbackTextureId = meshData.textureId
                    atlasPageIndex = -(meshData.textureId + 2)
                }
            } else if (meshData.textureId == -1 || !hasTexCoords) {
                atlasPageIndex = -1 // Untextured
            } else {
                // No atlas available but mesh has textures
                fallbackTextureId = meshData.textureId
                atlasPageIndex = -(meshData.textureId + 2)
            }

            val baseVertex = totalVertexCount
            val firstIndex = totalIndexCount

            // Accumulate vertex data
            positionAccumulator.add(meshData.positions)

            // Colors: use provided or default white
            val colors = meshData.colors ?: FloatArray(meshData.positions.size) { 1f }
            colorAccumulator.add(colors)

            // Texture coordinates (null for untextured)
            texCoordAccumulator.add(texCoords)

            // Normals: use provided or default up
            val normals = meshData.normals ?: FloatArray(meshData.positions.size) { i ->
                when (i % 3) { 1 -> 1f; else -> 0f }
            }
            normalAccumulator.add(normals)

            // Indices
            indexAccumulator.add(meshData.indices)

            totalVertexCount += numVertices
            totalIndexCount += meshData.indices.size

            val entryId = meshEntries.size
            meshEntries.add(MeshEntry(
                indexCount = meshData.indices.size,
                firstIndex = firstIndex,
                baseVertex = baseVertex,
                hasTexCoords = hasTexCoords,
                atlasPageIndex = atlasPageIndex,
                fallbackTextureId = fallbackTextureId
            ))
            entryIndices.add(entryId)
        }

        if (entryIndices.isNotEmpty()) {
            modelRegistry[modelId] = ResolvedModelBatch(entryIndices)
        }
    }

    /**
     * Check if a model is registered.
     */
    fun isModelRegistered(modelId: Int): Boolean = modelRegistry.containsKey(modelId)

    /**
     * Remap UV coordinates from local [0,1] space to atlas UV space.
     */
    private fun remapUVsToAtlas(texCoords: FloatArray, region: AtlasRegion): FloatArray {
        val remapped = FloatArray(texCoords.size)
        val uScale = region.u1 - region.u0
        val vScale = region.v1 - region.v0

        var i = 0
        while (i < texCoords.size - 1) {
            remapped[i] = region.u0 + texCoords[i] * uScale
            remapped[i + 1] = region.v0 + texCoords[i + 1] * vScale
            i += 2
        }
        return remapped
    }

    // ====================================================================
    // Geometry upload
    // ====================================================================

    /**
     * Build the shared VBO/IBO from all accumulated vertex/index data and set up the VAO.
     * Call this once after all models have been registered and the atlas has been uploaded.
     *
     * Also builds the texture array from atlas pages if applicable.
     */
    fun buildSharedGeometry() {
        if (totalVertexCount == 0 || totalIndexCount == 0) return

        glBindVertexArray(sharedVaoId)

        // Build shared VBO with interleaved-like separate attribute streams
        // Using separate VBOs per attribute for simplicity and compatibility with existing mesh format
        val posVbo = glGenBuffers()
        val posBuffer = MemoryUtil.memAllocFloat(totalVertexCount * 3)
        try {
            for (arr in positionAccumulator) posBuffer.put(arr)
            posBuffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(0, 0)
        } finally {
            MemoryUtil.memFree(posBuffer)
        }

        val colorVbo = glGenBuffers()
        val colBuffer = MemoryUtil.memAllocFloat(totalVertexCount * 3)
        try {
            for (arr in colorAccumulator) colBuffer.put(arr)
            colBuffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferData(GL_ARRAY_BUFFER, colBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(1, 0)
        } finally {
            MemoryUtil.memFree(colBuffer)
        }

        // Texture coordinates (with fallback zeros for untextured entries)
        val texVbo = glGenBuffers()
        val texBuffer = MemoryUtil.memAllocFloat(totalVertexCount * 2)
        try {
            for (i in texCoordAccumulator.indices) {
                val tc = texCoordAccumulator[i]
                if (tc != null) {
                    texBuffer.put(tc)
                } else {
                    // Fill with zeros for untextured vertices
                    val numVerticesInEntry = positionAccumulator[i].size / 3
                    for (j in 0 until numVerticesInEntry * 2) {
                        texBuffer.put(0f)
                    }
                }
            }
            texBuffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, texVbo)
            glBufferData(GL_ARRAY_BUFFER, texBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(2, 0)
        } finally {
            MemoryUtil.memFree(texBuffer)
        }

        val normalVbo = glGenBuffers()
        val normBuffer = MemoryUtil.memAllocFloat(totalVertexCount * 3)
        try {
            for (arr in normalAccumulator) normBuffer.put(arr)
            normBuffer.flip()
            glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
            glBufferData(GL_ARRAY_BUFFER, normBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(3)
            glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(3, 0)
        } finally {
            MemoryUtil.memFree(normBuffer)
        }

        // Store VBO IDs for cleanup
        sharedVboId = posVbo // Track first VBO; we'll clean up all in cleanup()

        // Build shared IBO
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

        // Track all VBOs for cleanup
        _vboIds.clear()
        _vboIds.addAll(listOf(posVbo, colorVbo, texVbo, normalVbo))

        // Clear CPU-side accumulators to free memory
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()

        // Build texture array from atlas pages
        buildTextureArray()

        geometryBuilt = true
    }

    /** VBO IDs for cleanup. */
    private val _vboIds = mutableListOf<Int>()

    /**
     * Build a GL_TEXTURE_2D_ARRAY from the object atlas pages.
     * Each atlas page becomes one layer in the array texture.
     */
    private fun buildTextureArray() {
        val atlas = objectAtlas ?: return
        val pageCount = atlas.getPageCount()
        if (pageCount == 0) return

        // Delete old texture array if it exists
        if (textureArrayId != 0) {
            glDeleteTextures(textureArrayId)
        }

        textureArrayId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId)

        // Get the atlas page size (all pages are the same size)
        // TextureAtlas uses atlasSize x atlasSize pages
        val pageSize = 4096 // Match the atlas page size

        // Allocate the 3D texture storage
        glTexImage3D(
            GL_TEXTURE_2D_ARRAY, 0, GL_RGBA,
            pageSize, pageSize, pageCount,
            0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?
        )

        // Copy each atlas page into a layer
        for (layerIndex in 0 until pageCount) {
            val pageTexId = atlas.getPageTextureId(layerIndex) ?: continue

            // Read pixels from the atlas page texture
            // We need to bind and read the page texture, then upload to the array layer
            // Since the atlas page is already a GL texture, we can use a framebuffer to copy
            // Or more simply, we can re-use the atlas pixel data

            // The simplest approach: bind the atlas page, read back pixels, upload to array layer
            // However, reading back from GPU is slow. Instead, we'll use glCopyImageSubData if available.
            // For maximum compatibility, we'll read back the pixels.

            val pixelBuffer = MemoryUtil.memAlloc(pageSize * pageSize * 4)
            try {
                glBindTexture(GL_TEXTURE_2D, pageTexId)
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer)
                glBindTexture(GL_TEXTURE_2D, 0)

                pixelBuffer.flip()

                glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId)
                glTexSubImage3D(
                    GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, layerIndex,
                    pageSize, pageSize, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE,
                    pixelBuffer
                )
            } finally {
                MemoryUtil.memFree(pixelBuffer)
            }
        }

        // Set texture parameters
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)

        textureArrayLayers = pageCount
    }

    // ====================================================================
    // Per-frame rendering
    // ====================================================================

    /**
     * Prepare for a new frame. Clears all per-frame instance data.
     */
    fun prepareFrame() {
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0
        _colorCommandCount = 0
        _texturedCommandCount = 0
        _colorInstanceBytes = 0L
        _texturedInstanceBytes = 0L
        meshInstanceCounts.clear()
        colorInstanceData.clear()
        texturedInstanceData.clear()
        colorBaseInstances.clear()
        texturedBaseInstances.clear()
    }

    /**
     * Add an instance of a model with pre-computed raw matrix data.
     *
     * @param modelId The model to instance
     * @param matrixData 16 floats representing a 4x4 column-major matrix
     * @return true if added successfully
     */
    fun addInstanceRaw(modelId: Int, matrixData: FloatArray): Boolean {
        val batch = modelRegistry[modelId] ?: return false

        for (entryIdx in batch.meshEntryIndices) {
            val entry = meshEntries[entryIdx]
            val count = (meshInstanceCounts[entryIdx] ?: 0)
            meshInstanceCounts[entryIdx] = count + 1

            if (entry.atlasPageIndex == -1) {
                // Untextured
                colorInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            } else {
                // Textured (atlas or fallback)
                texturedInstanceData.getOrPut(entryIdx) { mutableListOf() }.add(matrixData)
            }
        }

        totalInstancesThisFrame++
        return true
    }

    /**
     * Add an instance of a model with a Matrix4f.
     */
    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val matrixData = FloatArray(16)
        modelMatrix.get(matrixData)
        return addInstanceRaw(modelId, matrixData)
    }

    /**
     * Upload all per-frame instance data and build indirect draw commands.
     * Call after adding all instances for the frame.
     */
    fun uploadAll() {
        if (!geometryBuilt) return

        uploadColorInstances()
        uploadTexturedInstances()
    }

    /**
     * Upload untextured instance data and build color draw commands.
     * Writes directly to persistent mapped memory (or CPU staging buffer for fallback).
     */
    private fun uploadColorInstances() {
        if (colorInstanceData.isEmpty()) return

        val ssbo = colorSsbo ?: return
        val indirect = colorIndirectBuffer ?: return

        // Begin writing to the current frame's section of each persistent buffer
        val instanceBuf = ssbo.beginFrame()
        val cmdBuf = indirect.beginFrame()

        var instanceOffset = 0
        var commandCount = 0
        var instanceBytes = 0L

        // Sort by mesh entry ID for deterministic ordering
        for (entryIdx in colorInstanceData.keys.sorted()) {
            val instances = colorInstanceData[entryIdx] ?: continue
            if (instances.isEmpty()) continue

            val entry = meshEntries[entryIdx]
            colorBaseInstances[entryIdx] = instanceOffset

            // Write instance matrices to buffer (each matrix is 16 floats = 64 bytes)
            for (matrix in instances) {
                for (f in matrix) {
                    instanceBuf.putFloat(f)
                }
            }

            // Write DrawElementsIndirectCommand (5 uints = 20 bytes)
            cmdBuf.putInt(entry.indexCount)       // count
            cmdBuf.putInt(instances.size)          // instanceCount
            cmdBuf.putInt(entry.firstIndex)        // firstIndex
            cmdBuf.putInt(entry.baseVertex)        // baseVertex
            cmdBuf.putInt(instanceOffset)           // baseInstance

            instanceBytes += instances.size.toLong() * COLOR_INSTANCE_STRIDE
            instanceOffset += instances.size
            commandCount++

            if (commandCount >= MAX_COMMANDS) break
        }

        val cmdBytes = commandCount.toLong() * INDIRECT_COMMAND_SIZE

        // End frame: places fence (persistent) or uploads via glBufferSubData (fallback)
        ssbo.endFrame(instanceBytes)
        indirect.endFrame(cmdBytes)

        _colorCommandCount = commandCount
        _colorInstanceBytes = instanceBytes
    }

    /** Number of color draw commands for the current frame. */
    private var _colorCommandCount = 0

    /** Bytes of color instance data written this frame (for glBindBufferRange). */
    private var _colorInstanceBytes = 0L

    /**
     * Upload textured instance data and build textured draw commands.
     * Writes directly to persistent mapped memory (or CPU staging buffer for fallback).
     */
    private fun uploadTexturedInstances() {
        if (texturedInstanceData.isEmpty()) return

        val ssbo = texturedSsbo ?: return
        val indirect = texturedIndirectBuffer ?: return

        // Begin writing to the current frame's section of each persistent buffer
        val instanceBuf = ssbo.beginFrame()
        val cmdBuf = indirect.beginFrame()

        var instanceOffset = 0
        var commandCount = 0
        var instanceBytes = 0L

        // Sort by mesh entry ID for deterministic ordering
        for (entryIdx in texturedInstanceData.keys.sorted()) {
            val instances = texturedInstanceData[entryIdx] ?: continue
            if (instances.isEmpty()) continue

            val entry = meshEntries[entryIdx]
            texturedBaseInstances[entryIdx] = instanceOffset

            // Determine the texture layer index for this entry
            val textureLayer = if (entry.atlasPageIndex >= 0) {
                entry.atlasPageIndex
            } else {
                // Fallback textures: use layer 0 as placeholder
                // (these will be rendered in a separate pass if needed)
                0
            }

            // Write instance data: mat4 (64 bytes) + ivec4 (16 bytes) = 80 bytes per instance
            for (matrix in instances) {
                // mat4
                for (f in matrix) {
                    instanceBuf.putFloat(f)
                }
                // textureLayer (int) + 3 padding ints
                instanceBuf.putInt(textureLayer)
                instanceBuf.putInt(0) // padding
                instanceBuf.putInt(0) // padding
                instanceBuf.putInt(0) // padding
            }

            // Write DrawElementsIndirectCommand (5 uints = 20 bytes)
            cmdBuf.putInt(entry.indexCount)       // count
            cmdBuf.putInt(instances.size)          // instanceCount
            cmdBuf.putInt(entry.firstIndex)        // firstIndex
            cmdBuf.putInt(entry.baseVertex)        // baseVertex
            cmdBuf.putInt(instanceOffset)           // baseInstance

            instanceBytes += instances.size.toLong() * TEXTURED_INSTANCE_STRIDE
            instanceOffset += instances.size
            commandCount++

            if (commandCount >= MAX_COMMANDS) break
        }

        val cmdBytes = commandCount.toLong() * INDIRECT_COMMAND_SIZE

        // End frame: places fence (persistent) or uploads via glBufferSubData (fallback)
        ssbo.endFrame(instanceBytes)
        indirect.endFrame(cmdBytes)

        _texturedCommandCount = commandCount
        _texturedInstanceBytes = instanceBytes
    }

    /** Number of textured draw commands for the current frame. */
    private var _texturedCommandCount = 0

    /** Bytes of textured instance data written this frame (for glBindBufferRange). */
    private var _texturedInstanceBytes = 0L

    /**
     * Render all untextured instances with a single multi-draw-indirect call.
     *
     * For persistent mapped buffers, binds the correct triple-buffer section via offset.
     * For fallback buffers, binds from offset 0 (same as before).
     *
     * @param shader The indirect color shader program
     */
    fun renderUntextured(shader: ShaderProgram) {
        if (_colorCommandCount == 0 || !geometryBuilt) return

        val ssbo = colorSsbo ?: return
        val indirect = colorIndirectBuffer ?: return

        shader.bind()

        // Bind instance SSBO with range for the current frame's section
        ssbo.bindRange(INSTANCE_SSBO_BINDING, _colorInstanceBytes)

        // Bind VAO and indirect command buffer
        glBindVertexArray(sharedVaoId)
        indirect.bind()

        // For persistent buffers, the commands are at an offset within the triple buffer.
        // For fallback buffers, the offset is always 0.
        val cmdOffset = indirect.currentFrameReadOffset()

        // Single multi-draw-indirect call for ALL untextured objects
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, cmdOffset, _colorCommandCount, 0)

        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)

        drawCallsThisFrame++
    }

    /**
     * Render all textured instances with a single multi-draw-indirect call
     * using the texture array built from atlas pages.
     *
     * For persistent mapped buffers, binds the correct triple-buffer section via offset.
     * For fallback buffers, binds from offset 0 (same as before).
     *
     * @param shader The indirect textured shader program
     */
    fun renderTextured(shader: ShaderProgram) {
        if (_texturedCommandCount == 0 || !geometryBuilt) return
        if (textureArrayId == 0) return // No texture array built

        val ssbo = texturedSsbo ?: return
        val indirect = texturedIndirectBuffer ?: return

        shader.bind()

        // Bind texture array to unit 0
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId)

        // Bind instance SSBO with range for the current frame's section
        ssbo.bindRange(INSTANCE_SSBO_BINDING, _texturedInstanceBytes)

        // Bind VAO and indirect command buffer
        glBindVertexArray(sharedVaoId)
        indirect.bind()

        // For persistent buffers, the commands are at an offset within the triple buffer.
        // For fallback buffers, the offset is always 0.
        val cmdOffset = indirect.currentFrameReadOffset()

        // Single multi-draw-indirect call for ALL textured objects
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, cmdOffset, _texturedCommandCount, 0)

        indirect.unbind()
        glBindVertexArray(0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, 0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)

        drawCallsThisFrame++
    }

    // ====================================================================
    // Statistics
    // ====================================================================

    /**
     * Get draw calls issued this frame.
     */
    fun getDrawCallCount(): Int = drawCallsThisFrame

    /**
     * Get total instances submitted this frame.
     */
    fun getTotalInstances(): Int = totalInstancesThisFrame

    /**
     * Get the total number of triangles submitted by indirect rendering this frame.
     */
    fun getTotalTrianglesSubmitted(): Int {
        var total = 0
        for ((entryIdx, instances) in colorInstanceData) {
            if (instances.isNotEmpty()) {
                val entry = meshEntries[entryIdx]
                total += (entry.indexCount / 3) * instances.size
            }
        }
        for ((entryIdx, instances) in texturedInstanceData) {
            if (instances.isNotEmpty()) {
                val entry = meshEntries[entryIdx]
                total += (entry.indexCount / 3) * instances.size
            }
        }
        return total
    }

    /**
     * Get the number of registered models.
     */
    fun getRegisteredModelCount(): Int = modelRegistry.size

    /**
     * Get the number of mesh entries (total geometry batches across all models).
     */
    fun getMeshEntryCount(): Int = meshEntries.size

    /**
     * Whether the indirect renderer is initialized and ready.
     */
    fun isReady(): Boolean = initialized && geometryBuilt

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Clean up all GPU resources.
     */
    fun cleanup() {
        // Clean up persistent mapped buffers (handles fence sync, unmap, and buffer deletion)
        colorSsbo?.cleanup()
        colorSsbo = null
        texturedSsbo?.cleanup()
        texturedSsbo = null
        colorIndirectBuffer?.cleanup()
        colorIndirectBuffer = null
        texturedIndirectBuffer?.cleanup()
        texturedIndirectBuffer = null

        // Delete static geometry GL resources
        for (vbo in _vboIds) {
            glDeleteBuffers(vbo)
        }
        _vboIds.clear()

        if (sharedIboId != 0) {
            glDeleteBuffers(sharedIboId)
            sharedIboId = 0
        }
        if (sharedVaoId != 0) {
            glDeleteVertexArrays(sharedVaoId)
            sharedVaoId = 0
        }

        if (textureArrayId != 0) {
            glDeleteTextures(textureArrayId)
            textureArrayId = 0
        }

        // Clear registries
        meshEntries.clear()
        modelRegistry.clear()
        positionAccumulator.clear()
        colorAccumulator.clear()
        texCoordAccumulator.clear()
        normalAccumulator.clear()
        indexAccumulator.clear()

        totalVertexCount = 0
        totalIndexCount = 0
        _colorCommandCount = 0
        _texturedCommandCount = 0
        _colorInstanceBytes = 0L
        _texturedInstanceBytes = 0L
        initialized = false
        geometryBuilt = false
    }
}
