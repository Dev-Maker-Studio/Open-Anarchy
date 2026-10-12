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

class InstancedMesh(
    private var positions: FloatArray?,
    private var colors: FloatArray? = null,
    private var textureCoords: FloatArray? = null,
    private var normals: FloatArray? = null,
    private var indices: IntArray?,
    private val maxInstances: Int = 10000
) {
    private var vaoId: Int = 0
    private val vboIds = mutableListOf<Int>()
    private var indexVboId: Int = 0
    private var instanceVboId: Int = 0

    private var glReady = false

    val vertexCount: Int = indices?.size ?: 0

    private var instanceBuffer: FloatBuffer? = null

    private var instanceCapacity = 0
    private var instanceCount = 0
    private var instanceDataDirty = false

    private val tempFloats = FloatArray(16)

    private fun createGlResources() {
        if (glReady) return
        glReady = true

        val positions = this.positions ?: FloatArray(0)
        val colors = this.colors
        val textureCoords = this.textureCoords
        val normals = this.normals
        val indices = this.indices ?: IntArray(0)

        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        val posBuffer = MemoryUtil.memAllocFloat(positions.size)
        try {
            posBuffer.put(positions).flip()
            val posVbo = glGenBuffers()
            vboIds.add(posVbo)
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(0, 0)
        } finally {
            MemoryUtil.memFree(posBuffer)
        }

        val colorData = colors ?: FloatArray(positions.size) { 1f }
        val colorBuffer = MemoryUtil.memAllocFloat(colorData.size)
        try {
            colorBuffer.put(colorData).flip()
            val colorVbo = glGenBuffers()
            vboIds.add(colorVbo)
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferData(GL_ARRAY_BUFFER, colorBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glVertexAttribDivisor(1, 0)
        } finally {
            MemoryUtil.memFree(colorBuffer)
        }

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
                glVertexAttribDivisor(2, 0)
            } finally {
                MemoryUtil.memFree(texBuffer)
            }
        }

        val normalData = normals ?: FloatArray(positions.size) { i ->
            when (i % 3) {
                1 -> 1f
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
            glVertexAttribDivisor(3, 0)
        } finally {
            MemoryUtil.memFree(normalBuffer)
        }

        instanceVboId = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        glBufferData(GL_ARRAY_BUFFER, 0L, GL_DYNAMIC_DRAW)

        val vec4Size = 4 * 4
        val mat4Size = vec4Size * 4

        for (i in 0 until 4) {
            val attribIndex = 4 + i
            glEnableVertexAttribArray(attribIndex)
            glVertexAttribPointer(attribIndex, 4, GL_FLOAT, false, mat4Size, (i * vec4Size).toLong())
            glVertexAttribDivisor(attribIndex, 1)
        }

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

        this.positions = null
        this.colors = null
        this.textureCoords = null
        this.normals = null
        this.indices = null
    }

    fun beginBatch() {
        instanceBuffer?.clear()
        instanceCount = 0
        instanceDataDirty = true
    }

    private fun instanceSlot(): FloatBuffer? {
        if (instanceCount >= maxInstances) return null
        createGlResources()
        val existing = instanceBuffer
        if (existing != null && instanceCount < instanceCapacity) return existing

        var capacity = if (instanceCapacity == 0) INITIAL_INSTANCE_CAPACITY else instanceCapacity * 2
        while (capacity <= instanceCount) capacity *= 2
        if (capacity > maxInstances) capacity = maxInstances

        val grown = if (existing == null) {
            MemoryUtil.memAllocFloat(capacity * 16)
        } else {
            val position = existing.position()
            MemoryUtil.memRealloc(existing, capacity * 16).also { it.position(position) }
        }
        instanceBuffer = grown
        instanceCapacity = capacity

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        glBufferData(GL_ARRAY_BUFFER, (capacity * 16 * 4).toLong(), GL_DYNAMIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return grown
    }

    fun addInstance(modelMatrix: Matrix4f): Boolean {
        val buffer = instanceSlot() ?: return false

        modelMatrix.get(tempFloats)
        buffer.put(tempFloats)
        instanceCount++
        return true
    }

    fun addInstanceRaw(matrixData: FloatArray): Boolean {
        val buffer = instanceSlot() ?: return false

        buffer.put(matrixData)
        instanceCount++
        return true
    }

    fun addInstance(x: Float, y: Float, z: Float, scale: Float = 1f): Boolean {
        val buffer = instanceSlot() ?: return false

        tempFloats[0] = scale;  tempFloats[1] = 0f;     tempFloats[2] = 0f;     tempFloats[3] = 0f
        tempFloats[4] = 0f;     tempFloats[5] = scale;  tempFloats[6] = 0f;     tempFloats[7] = 0f
        tempFloats[8] = 0f;     tempFloats[9] = 0f;     tempFloats[10] = scale; tempFloats[11] = 0f
        tempFloats[12] = x;     tempFloats[13] = y;     tempFloats[14] = z;     tempFloats[15] = 1f

        buffer.put(tempFloats)
        instanceCount++
        return true
    }

    fun endBatch() {
        if (!instanceDataDirty || instanceCount == 0) return
        val buffer = instanceBuffer ?: return

        buffer.flip()

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId)
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        instanceDataDirty = false
    }

    fun render() {
        if (instanceCount == 0) return

        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
    }

    fun renderWireframe() {
        if (instanceCount == 0) return

        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElementsInstanced(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0, instanceCount)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    fun getInstanceCount(): Int = instanceCount

    fun isFull(): Boolean = instanceCount >= maxInstances

    fun cleanup() {
        positions = null
        colors = null
        textureCoords = null
        normals = null
        indices = null

        if (glReady) {
            glDisableVertexAttribArray(0)
            glBindBuffer(GL_ARRAY_BUFFER, 0)

            vboIds.forEach { glDeleteBuffers(it) }
            glDeleteBuffers(instanceVboId)
            glDeleteBuffers(indexVboId)

            glBindVertexArray(0)
            glDeleteVertexArrays(vaoId)
            glReady = false
        }

        instanceBuffer?.let { MemoryUtil.memFree(it) }
        instanceBuffer = null
        instanceCapacity = 0
    }

    companion object {
        private const val INITIAL_INSTANCE_CAPACITY = 64

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
