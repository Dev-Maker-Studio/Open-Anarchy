package org.darkan.tools.engine.render

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL31.glDrawElementsInstanced
import org.lwjgl.opengl.GL33.glVertexAttribDivisor
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer

/**
 * A mesh that supports GPU instancing for rendering many copies efficiently.
 *
 * Instance data (model matrices) are stored in a VBO and updated per-frame.
 * Uses vertex attribute divisor to feed one matrix per instance.
 *
 * Vertex attribute layout:
 * - 0: position (vec3)
 * - 1: color (vec3)
 * - 2: texCoord (vec2)
 * - 3: normal (vec3)
 * - 4-7: instanceMatrix (mat4, one vec4 per column)
 */
class InstancedMesh(
    positions: FloatArray,
    colors: FloatArray? = null,
    textureCoords: FloatArray? = null,
    normals: FloatArray? = null,
    indices: IntArray,
    private val maxInstances: Int = 10000
) {
    val vaoId: Int
    private val vboIds = mutableListOf<Int>()
    private val indexVboId: Int
    private val instanceVboId: Int
    val vertexCount: Int = indices.size

    // Instance data buffer (16 floats per matrix)
    private val instanceBuffer: FloatBuffer = MemoryUtil.memAllocFloat(maxInstances * 16)
    private var instanceCount = 0
    private var instanceDataDirty = false

    // Temp matrix for storing transforms
    private val tempFloats = FloatArray(16)

    init {
        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        // Position VBO (attribute 0)
        val posBuffer = MemoryUtil.memAllocFloat(positions.size)
        try {
            posBuffer.put(positions).flip()
            val posVbo = glGenBuffers()
            vboIds.add(posVbo)
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(0, 0) // Per-vertex, not per-instance
        } finally {
            MemoryUtil.memFree(posBuffer)
        }

        // Color VBO (attribute 1)
        // Always set up colors - use provided colors or default to white (1,1,1) for all vertices
        val colorData = colors ?: FloatArray(positions.size) { 1f } // Default white if no colors
        val colorBuffer = MemoryUtil.memAllocFloat(colorData.size)
        try {
            colorBuffer.put(colorData).flip()
            val colorVbo = glGenBuffers()
            vboIds.add(colorVbo)
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferData(GL_ARRAY_BUFFER, colorBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(1, 0) // Per-vertex, not per-instance
        } finally {
            MemoryUtil.memFree(colorBuffer)
        }

        // Texture coordinate VBO (attribute 2)
        if (textureCoords != null) {
            val texBuffer = MemoryUtil.memAllocFloat(textureCoords.size)
            try {
                texBuffer.put(textureCoords).flip()
                val texVbo = glGenBuffers()
                vboIds.add(texVbo)
                glBindBuffer(GL_ARRAY_BUFFER, texVbo)
                glBufferData(GL_ARRAY_BUFFER, texBuffer, GL_STATIC_DRAW)
                glEnableVertexAttribArray(2)
                glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
                glVertexAttribDivisor(2, 0) // Per-vertex
            } finally {
                MemoryUtil.memFree(texBuffer)
            }
        }

        // Normal VBO (attribute 3)
        // Always set up normals - use provided normals or default to up (0,1,0) for all vertices
        val normalData = normals ?: FloatArray(positions.size) { i ->
            when (i % 3) {
                1 -> 1f  // Y component = 1 (up)
                else -> 0f
            }
        }
        val normalBuffer = MemoryUtil.memAllocFloat(normalData.size)
        try {
            normalBuffer.put(normalData).flip()
            val normalVbo = glGenBuffers()
            vboIds.add(normalVbo)
            glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
            glBufferData(GL_ARRAY_BUFFER, normalBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(3)
            glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(3, 0) // Per-vertex
        } finally {
            MemoryUtil.memFree(normalBuffer)
        }

        // Instance matrix VBO (attributes 4-7)
        instanceVboId = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        // Allocate buffer for max instances, will be updated each frame
        glBufferData(GL_ARRAY_BUFFER, (maxInstances * 16 * 4).toLong(), GL_DYNAMIC_DRAW)

        val vec4Size = 4 * 4 // 4 floats * 4 bytes
        val mat4Size = vec4Size * 4 // 4 vec4s per mat4

        // Set up 4 vertex attributes for the 4 columns of the matrix
        for (i in 0 until 4) {
            val attribIndex = 4 + i
            glEnableVertexAttribArray(attribIndex)
            glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, mat4Size, (i * vec4Size).toLong())
            glVertexAttribDivisor(attribIndex, 1) // One per instance
        }

        // Index VBO
        val indexBuffer = MemoryUtil.memAllocInt(indices.size)
        try {
            indexBuffer.put(indices).flip()
            indexVboId = glGenBuffers()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexVboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(indexBuffer)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    /**
     * Begin a new batch of instances.
     * Clears the instance buffer for new data.
     */
    fun beginBatch() {
        instanceBuffer.clear()
        instanceCount = 0
        instanceDataDirty = true
    }

    /**
     * Add an instance with the given model matrix.
     *
     * @return true if the instance was added, false if buffer is full
     */
    fun addInstance(modelMatrix: Matrix4f): Boolean {
        if (instanceCount >= maxInstances) {
            return false
        }

        // Get matrix data as column-major floats
        modelMatrix.get(tempFloats)
        instanceBuffer.put(tempFloats)
        instanceCount++
        return true
    }

    /**
     * Add an instance at a position with optional scale.
     */
    fun addInstance(x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        if (instanceCount >= maxInstances) {
            return false
        }

        // Build a simple TRS matrix inline
        tempFloats[0] = scale;  tempFloats[1] = 0f;     tempFloats[2] = 0f;     tempFloats[3] = 0f
        tempFloats[4] = 0f;     tempFloats[5] = scale;  tempFloats[6] = 0f;     tempFloats[7] = 0f
        tempFloats[8] = 0f;     tempFloats[9] = 0f;     tempFloats[10] = scale; tempFloats[11] = 0f
        tempFloats[12] = x;     tempFloats[13] = y;     tempFloats[14] = z;     tempFloats[15] = 1f

        instanceBuffer.put(tempFloats)
        instanceCount++
        return true
    }

    /**
     * End the batch and upload instance data to GPU.
     */
    fun endBatch() {
        if (!instanceDataDirty || instanceCount == 0) return

        instanceBuffer.flip()

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        // Use glBufferSubData for partial updates (more efficient than glBufferData)
        glBufferSubData(GL_ARRAY_BUFFER, 0, instanceBuffer)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        instanceDataDirty = false
    }

    /**
     * Render all instances.
     */
    fun render() {
        if (instanceCount == 0) return

        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
    }

    /**
     * Render instances in wireframe mode.
     */
    fun renderWireframe() {
        if (instanceCount == 0) return

        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    /**
     * Get current instance count.
     */
    fun getInstanceCount(): Int = instanceCount

    /**
     * Check if the instance buffer is full.
     */
    fun isFull(): Boolean = instanceCount >= maxInstances

    /**
     * Clean up GPU resources.
     */
    fun cleanup() {
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        vboIds.forEach { glDeleteBuffers(it) }
        glDeleteBuffers(instanceVboId)
        glDeleteBuffers(indexVboId)

        glBindVertexArray(0)
        glDeleteVertexArrays(vaoId)

        MemoryUtil.memFree(instanceBuffer)
    }

    companion object {
        /**
         * Create an instanced mesh from an existing Mesh's data.
         * Note: The original Mesh should not be cleaned up while this is in use
         * as this creates new GPU resources.
         */
        fun fromMeshData(
            positions: FloatArray,
            colors: FloatArray? = null,
            textureCoords: FloatArray? = null,
            normals: FloatArray? = null,
            indices: IntArray,
            maxInstances: Int = 10000
        ): InstancedMesh {
            return InstancedMesh(positions, colors, textureCoords, normals, indices, maxInstances)
        }
    }
}
