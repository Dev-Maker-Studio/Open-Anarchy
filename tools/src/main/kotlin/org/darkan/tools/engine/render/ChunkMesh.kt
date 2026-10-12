package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil

class ChunkMesh private constructor(
    private var maxVertices: Int,
    val hasTexcoords: Boolean,
    val hasAlpha: Boolean
) {
    var vaoId: Int = 0; private set
    private var posVbo: Int = 0
    private var colorVbo: Int = 0
    private var texcoordVbo: Int = 0
    private var normalVbo: Int = 0
    private var alphaVbo: Int = 0

    var vertexCount: Int = 0; private set

    private fun init() {
        vaoId = glGenVertexArrays()
        glBindVertexArray(vaoId)

        posVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)

        colorVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)

        if (hasTexcoords) {
            texcoordVbo = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 2 * 4).toLong(), GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
        }

        normalVbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(3)
        glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)

        if (hasAlpha) {
            alphaVbo = glGenBuffers()
            glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 4).toLong(), GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(4)
            glVertexAttribPointer(4, 1, GL_FLOAT, false, 0, 0)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private fun grow(newMax: Int) {
        maxVertices = newMax
        glBindVertexArray(vaoId)

        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        if (hasTexcoords) {
            glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 2 * 4).toLong(), GL_DYNAMIC_DRAW)
        }

        glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
        glBufferData(GL_ARRAY_BUFFER, (maxVertices * 3 * 4).toLong(), GL_DYNAMIC_DRAW)

        if (hasAlpha) {
            glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
            glBufferData(GL_ARRAY_BUFFER, (maxVertices * 4).toLong(), GL_DYNAMIC_DRAW)
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    fun upload(
        positions: FloatArray, colors: FloatArray,
        texcoords: FloatArray?, normals: FloatArray,
        alphas: FloatArray?, count: Int
    ) {
        if (count > maxVertices) {
            var newMax = maxVertices
            while (newMax < count) newMax *= 2
            System.err.println("[ChunkMesh] Buffer overflow: $count vertices exceeds capacity $maxVertices, growing to $newMax")
            grow(newMax)
        }

        vertexCount = count
        if (count == 0) return

        glBindVertexArray(vaoId)

        var buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(positions, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(colors, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        if (hasTexcoords && texcoords != null) {
            buffer = MemoryUtil.memAllocFloat(count * 2)
            try {
                buffer.put(texcoords, 0, count * 2).flip()
                glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo)
                glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
            } finally {
                MemoryUtil.memFree(buffer)
            }
        }

        buffer = MemoryUtil.memAllocFloat(count * 3)
        try {
            buffer.put(normals, 0, count * 3).flip()
            glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        if (hasAlpha && alphas != null) {
            buffer = MemoryUtil.memAllocFloat(count)
            try {
                buffer.put(alphas, 0, count).flip()
                glBindBuffer(GL_ARRAY_BUFFER, alphaVbo)
                glBufferSubData(GL_ARRAY_BUFFER, 0, buffer)
            } finally {
                MemoryUtil.memFree(buffer)
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    fun render() {
        if (vertexCount == 0) return
        glBindVertexArray(vaoId)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        glBindVertexArray(0)
    }

    fun isEmpty(): Boolean = vertexCount == 0

    fun cleanup() {
        glBindVertexArray(0)
        glDeleteBuffers(posVbo)
        glDeleteBuffers(colorVbo)
        if (hasTexcoords) glDeleteBuffers(texcoordVbo)
        glDeleteBuffers(normalVbo)
        if (hasAlpha) glDeleteBuffers(alphaVbo)
        glDeleteVertexArrays(vaoId)
    }

    companion object {
        fun createColor(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = false, hasAlpha = false).also { it.init() }
        }

        fun createTextured(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = true, hasAlpha = false).also { it.init() }
        }

        fun createAlpha(maxVertices: Int): ChunkMesh {
            return ChunkMesh(maxVertices, hasTexcoords = true, hasAlpha = true).also { it.init() }
        }
    }
}
