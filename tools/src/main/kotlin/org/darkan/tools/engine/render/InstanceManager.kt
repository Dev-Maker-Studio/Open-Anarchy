package org.darkan.tools.engine.render

import org.joml.Matrix4f

/**
 * Data for creating an instanced mesh from model geometry.
 */
data class MeshData(
    val positions: FloatArray,
    val colors: FloatArray?,
    val textureCoords: FloatArray?,
    val normals: FloatArray?,
    val indices: IntArray,
    val textureId: Int = -1 // -1 for untextured
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshData) return false
        return positions.contentEquals(other.positions) &&
               indices.contentEquals(other.indices) &&
               textureId == other.textureId
    }

    override fun hashCode(): Int {
        var result = positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + textureId
        return result
    }
}

/**
 * Batch key for grouping instances by model and material.
 */
data class InstanceBatchKey(
    val modelId: Int,
    val textureId: Int
)

/**
 * Manages instanced rendering for many objects sharing the same model.
 *
 * Usage:
 * 1. Call prepareFrame() at the start of each frame
 * 2. Call addInstance() for each object to render
 * 3. Call uploadAll() to send data to GPU
 * 4. Call renderAll() to draw all instances
 */
class InstanceManager(
    private val maxInstancesPerMesh: Int = 10000
) {
    // Registered mesh data by model ID
    private val meshRegistry = mutableMapOf<Int, MutableList<MeshData>>()

    // Instanced meshes by batch key
    private val instancedMeshes = mutableMapOf<InstanceBatchKey, InstancedMesh>()

    // Pending instances for current frame
    private val pendingInstances = mutableMapOf<InstanceBatchKey, MutableList<Matrix4f>>()

    // Statistics
    private var totalInstancesThisFrame = 0
    private var drawCallsThisFrame = 0

    /**
     * Register mesh data for a model ID.
     * Call this once when loading models.
     *
     * @param modelId The model identifier
     * @param meshDataList List of mesh data (one per texture batch)
     */
    fun registerModel(modelId: Int, meshDataList: List<MeshData>) {
        meshRegistry[modelId] = meshDataList.toMutableList()

        // Create instanced meshes for each batch
        for (meshData in meshDataList) {
            // Skip empty batches
            if (meshData.positions.isEmpty() || meshData.indices.isEmpty()) {
                continue
            }

            val key = InstanceBatchKey(modelId, meshData.textureId)
            if (!instancedMeshes.containsKey(key)) {
                instancedMeshes[key] = InstancedMesh(
                    positions = meshData.positions,
                    colors = meshData.colors,
                    textureCoords = meshData.textureCoords,
                    normals = meshData.normals,
                    indices = meshData.indices,
                    maxInstances = maxInstancesPerMesh
                )
            }
        }
    }

    /**
     * Check if a model is registered.
     */
    fun isModelRegistered(modelId: Int): Boolean = meshRegistry.containsKey(modelId)

    /**
     * Prepare for a new frame. Clears all pending instances.
     */
    fun prepareFrame() {
        pendingInstances.clear()
        totalInstancesThisFrame = 0
        drawCallsThisFrame = 0

        // Begin batch on all instanced meshes
        for (mesh in instancedMeshes.values) {
            mesh.beginBatch()
        }
    }

    /**
     * Add an instance of a model with the given transform.
     *
     * @param modelId The model to instance
     * @param modelMatrix The transform matrix for this instance
     * @return true if added successfully, false if model not registered or buffer full
     */
    fun addInstance(modelId: Int, modelMatrix: Matrix4f): Boolean {
        val meshDataList = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (meshData in meshDataList) {
            val key = InstanceBatchKey(modelId, meshData.textureId)
            val mesh = instancedMeshes[key] ?: continue

            if (!mesh.addInstance(modelMatrix)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    /**
     * Add an instance at a simple position.
     */
    fun addInstance(modelId: Int, x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        val meshDataList = meshRegistry[modelId] ?: return false

        var allAdded = true
        for (meshData in meshDataList) {
            val key = InstanceBatchKey(modelId, meshData.textureId)
            val mesh = instancedMeshes[key] ?: continue

            if (!mesh.addInstance(x, y, z, scale)) {
                allAdded = false
            }
        }

        if (allAdded) {
            totalInstancesThisFrame++
        }

        return allAdded
    }

    /**
     * Upload all instance data to GPU.
     * Call this after adding all instances for the frame.
     */
    fun uploadAll() {
        for (mesh in instancedMeshes.values) {
            mesh.endBatch()
        }
    }

    /**
     * Render all instanced meshes without textures (color shader).
     *
     * @param shader The shader to use (should have model matrix support disabled for instancing)
     * @param setupCallback Called before rendering each batch for shader setup
     */
    fun renderUntextured(
        shader: ShaderProgram,
        setupCallback: ((InstanceBatchKey) -> Unit)? = null
    ) {
        shader.bind()

        for ((key, mesh) in instancedMeshes) {
            if (key.textureId != -1) continue // Skip textured batches
            if (mesh.getInstanceCount() == 0) continue

            setupCallback?.invoke(key)
            mesh.render()
            drawCallsThisFrame++
        }
    }

    /**
     * Render all instanced meshes with textures.
     *
     * @param shader The shader to use
     * @param bindTexture Callback to bind texture for each batch
     */
    fun renderTextured(
        shader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        shader.bind()

        for ((key, mesh) in instancedMeshes) {
            if (key.textureId == -1) continue // Skip untextured batches
            if (mesh.getInstanceCount() == 0) continue

            bindTexture(key.textureId)
            mesh.render()
            drawCallsThisFrame++
        }
    }

    /**
     * Render all instances (both textured and untextured).
     *
     * @param colorShader Shader for untextured geometry
     * @param texturedShader Shader for textured geometry
     * @param bindTexture Callback to bind texture
     */
    fun renderAll(
        colorShader: ShaderProgram,
        texturedShader: ShaderProgram,
        bindTexture: (textureId: Int) -> Unit
    ) {
        // Render untextured first
        renderUntextured(colorShader)

        // Then textured
        renderTextured(texturedShader, bindTexture)
    }

    /**
     * Get statistics for the current frame.
     */
    fun getStats(): InstanceStats {
        val instanceCounts = instancedMeshes.mapValues { it.value.getInstanceCount() }
        return InstanceStats(
            totalInstances = totalInstancesThisFrame,
            drawCalls = drawCallsThisFrame,
            registeredModels = meshRegistry.size,
            instancedMeshCount = instancedMeshes.size,
            instancesPerBatch = instanceCounts
        )
    }

    /**
     * Clean up all GPU resources.
     */
    fun cleanup() {
        for (mesh in instancedMeshes.values) {
            mesh.cleanup()
        }
        instancedMeshes.clear()
        meshRegistry.clear()
        pendingInstances.clear()
    }

    /**
     * Unregister a model and clean up its resources.
     */
    fun unregisterModel(modelId: Int) {
        val meshDataList = meshRegistry.remove(modelId) ?: return

        for (meshData in meshDataList) {
            val key = InstanceBatchKey(modelId, meshData.textureId)
            instancedMeshes.remove(key)?.cleanup()
        }
    }
}

data class InstanceStats(
    val totalInstances: Int,
    val drawCalls: Int,
    val registeredModels: Int,
    val instancedMeshCount: Int,
    val instancesPerBatch: Map<InstanceBatchKey, Int>
)
