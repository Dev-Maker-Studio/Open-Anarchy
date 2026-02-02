package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer
import java.nio.IntBuffer

class Mesh(
    positions: FloatArray,
    colors: FloatArray? = null,
    textureCoords: FloatArray? = null,
    normals: FloatArray? = null,
    indices: IntArray,
    /** Number of components per color vertex: 3 for RGB, 4 for RGBA */
    colorComponents: Int = 3
) {
    var vaoId: Int = 0
        private set
    private val vboIds = mutableListOf<Int>()
    val vertexCount: Int = indices.size

    init {
        var posBuffer: FloatBuffer? = null
        var colorBuffer: FloatBuffer? = null
        var texBuffer: FloatBuffer? = null
        var normalBuffer: FloatBuffer? = null
        var indexBuffer: IntBuffer? = null

        try {
            vaoId = glGenVertexArrays()
            glBindVertexArray(vaoId)

            // Position VBO
            posBuffer = MemoryUtil.memAllocFloat(positions.size)
            posBuffer.put(positions).flip()
            val posVbo = glGenBuffers()
            vboIds.add(posVbo)
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)

            // Color VBO (supports 3 or 4 components for RGB or RGBA)
            if (colors != null) {
                colorBuffer = MemoryUtil.memAllocFloat(colors.size)
                colorBuffer.put(colors).flip()
                val colorVbo = glGenBuffers()
                vboIds.add(colorVbo)
                glBindBuffer(GL_ARRAY_BUFFER, colorVbo)
                glBufferData(GL_ARRAY_BUFFER, colorBuffer, GL_STATIC_DRAW)
                glEnableVertexAttribArray(1)
                glVertexAttribPointer(1, colorComponents, GL_FLOAT, false, 0, 0)
            }

            // Texture coordinate VBO
            if (textureCoords != null) {
                texBuffer = MemoryUtil.memAllocFloat(textureCoords.size)
                texBuffer.put(textureCoords).flip()
                val texVbo = glGenBuffers()
                vboIds.add(texVbo)
                glBindBuffer(GL_ARRAY_BUFFER, texVbo)
                glBufferData(GL_ARRAY_BUFFER, texBuffer, GL_STATIC_DRAW)
                glEnableVertexAttribArray(2)
                glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
            }

            // Normal VBO
            if (normals != null) {
                normalBuffer = MemoryUtil.memAllocFloat(normals.size)
                normalBuffer.put(normals).flip()
                val normalVbo = glGenBuffers()
                vboIds.add(normalVbo)
                glBindBuffer(GL_ARRAY_BUFFER, normalVbo)
                glBufferData(GL_ARRAY_BUFFER, normalBuffer, GL_STATIC_DRAW)
                glEnableVertexAttribArray(3)
                glVertexAttribPointer(3, 3, GL_FLOAT, false, 0, 0)
            }

            // Index VBO
            indexBuffer = MemoryUtil.memAllocInt(indices.size)
            indexBuffer.put(indices).flip()
            val indexVbo = glGenBuffers()
            vboIds.add(indexVbo)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexVbo)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW)

            glBindBuffer(GL_ARRAY_BUFFER, 0)
            glBindVertexArray(0)
        } finally {
            posBuffer?.let { MemoryUtil.memFree(it) }
            colorBuffer?.let { MemoryUtil.memFree(it) }
            texBuffer?.let { MemoryUtil.memFree(it) }
            normalBuffer?.let { MemoryUtil.memFree(it) }
            indexBuffer?.let { MemoryUtil.memFree(it) }
        }
    }

    var primitiveType: Int = GL_TRIANGLES

    fun render() {
        glBindVertexArray(vaoId)
        glDrawElements(primitiveType, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    fun renderWireframe() {
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    fun cleanup() {
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        vboIds.forEach { glDeleteBuffers(it) }
        glBindVertexArray(0)
        glDeleteVertexArrays(vaoId)
    }

    companion object {
        fun createTriangle(): Mesh {
            val positions = floatArrayOf(
                0.0f, 0.5f, 0.0f,
                -0.5f, -0.5f, 0.0f,
                0.5f, -0.5f, 0.0f
            )
            val colors = floatArrayOf(
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 1.0f
            )
            val indices = intArrayOf(0, 1, 2)
            return Mesh(positions, colors, null, null, indices)
        }

        fun createCube(size: Float = 1.0f): Mesh {
            val s = size / 2
            val positions = floatArrayOf(
                // Front face
                -s, -s, s,  s, -s, s,  s, s, s,  -s, s, s,
                // Back face
                -s, -s, -s,  -s, s, -s,  s, s, -s,  s, -s, -s,
                // Top face
                -s, s, -s,  -s, s, s,  s, s, s,  s, s, -s,
                // Bottom face
                -s, -s, -s,  s, -s, -s,  s, -s, s,  -s, -s, s,
                // Right face
                s, -s, -s,  s, s, -s,  s, s, s,  s, -s, s,
                // Left face
                -s, -s, -s,  -s, -s, s,  -s, s, s,  -s, s, -s
            )
            val colors = FloatArray(positions.size) { 0.7f }
            val indices = intArrayOf(
                0, 1, 2, 0, 2, 3,       // Front
                4, 5, 6, 4, 6, 7,       // Back
                8, 9, 10, 8, 10, 11,    // Top
                12, 13, 14, 12, 14, 15, // Bottom
                16, 17, 18, 16, 18, 19, // Right
                20, 21, 22, 20, 22, 23  // Left
            )
            return Mesh(positions, colors, null, null, indices)
        }

        fun createGrid(size: Float, divisions: Int): Mesh {
            val positions = mutableListOf<Float>()
            val colors = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            val indices = mutableListOf<Int>()

            val step = size / divisions
            val halfSize = size / 2
            var index = 0

            for (i in 0..divisions) {
                val pos = -halfSize + i * step
                // Lines along Z
                positions.addAll(listOf(pos, 0f, -halfSize))
                positions.addAll(listOf(pos, 0f, halfSize))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                normals.addAll(listOf(0f, 1f, 0f))
                normals.addAll(listOf(0f, 1f, 0f))
                indices.add(index++)
                indices.add(index++)

                // Lines along X
                positions.addAll(listOf(-halfSize, 0f, pos))
                positions.addAll(listOf(halfSize, 0f, pos))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                normals.addAll(listOf(0f, 1f, 0f))
                normals.addAll(listOf(0f, 1f, 0f))
                indices.add(index++)
                indices.add(index++)
            }

            return Mesh(
                positions.toFloatArray(),
                colors.toFloatArray(),
                null,
                normals.toFloatArray(),
                indices.toIntArray()
            ).apply { primitiveType = GL_LINES }
        }
    }
}
