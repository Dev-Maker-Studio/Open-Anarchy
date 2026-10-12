package org.darkan.tools.engine.render

import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL14.glMultiDrawElements
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer

class Mesh(buffers: MeshBuffers) {
    var vaoId: Int = 0
        private set
    private val vboIds = mutableListOf<Int>()
    val vertexCount: Int = buffers.indexCount

    constructor(
        positions: FloatArray,
        colors: FloatArray? = null,
        textureCoords: FloatArray? = null,
        normals: FloatArray? = null,
        indices: IntArray,
        colorComponents: Int = 3
    ) : this(
        MeshBuffers.pack(
            positions = positions,
            colors = colors,
            texCoords = textureCoords,
            normals = normals,
            indices = indices,
            colorComponents = colorComponents
        )
    )

    init {
        try {
            vaoId = glGenVertexArrays()
            glBindVertexArray(vaoId)

            val vertexVbo = glGenBuffers()
            vboIds.add(vertexVbo)
            glBindBuffer(GL_ARRAY_BUFFER, vertexVbo)
            glBufferData(GL_ARRAY_BUFFER, buffers.vertexData, GL_STATIC_DRAW)
            for (attribute in buffers.attributes) {
                glEnableVertexAttribArray(attribute.location)
                glVertexAttribPointer(
                    attribute.location,
                    attribute.components,
                    attribute.type,
                    attribute.normalized,
                    0,
                    attribute.byteOffset.toLong()
                )
            }

            val indexVbo = glGenBuffers()
            vboIds.add(indexVbo)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexVbo)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, buffers.indices, GL_STATIC_DRAW)

            glBindBuffer(GL_ARRAY_BUFFER, 0)
            glBindVertexArray(0)
        } finally {
            buffers.free()
        }
    }

    var primitiveType: Int = GL_TRIANGLES

    fun render() {
        glBindVertexArray(vaoId)
        glDrawElements(primitiveType, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    private var multiDrawCounts: IntBuffer? = null
    private var multiDrawOffsets: PointerBuffer? = null
    private var multiDrawCapacity = 0

    fun renderRange(firstIndex: Int, count: Int) {
        if (count <= 0) return
        glBindVertexArray(vaoId)
        glDrawElements(primitiveType, count, GL_UNSIGNED_INT, firstIndex.toLong() * Int.SIZE_BYTES)
        glBindVertexArray(0)
    }

    fun renderRanges(firstIndices: IntArray, counts: IntArray, rangeCount: Int) {
        if (rangeCount <= 0) return
        ensureMultiDrawCapacity(rangeCount)
        val countBuffer = multiDrawCounts!!
        val offsetBuffer = multiDrawOffsets!!
        for (i in 0 until rangeCount) {
            countBuffer.put(i, counts[i])
            offsetBuffer.put(i, firstIndices[i].toLong() * Int.SIZE_BYTES)
        }
        countBuffer.position(0)
        countBuffer.limit(rangeCount)
        offsetBuffer.position(0)
        offsetBuffer.limit(rangeCount)

        glBindVertexArray(vaoId)
        glMultiDrawElements(primitiveType, countBuffer, GL_UNSIGNED_INT, offsetBuffer)
        glBindVertexArray(0)
    }

    private fun ensureMultiDrawCapacity(rangeCount: Int) {
        if (multiDrawCapacity >= rangeCount && multiDrawCounts != null) {
            multiDrawCounts!!.clear()
            multiDrawOffsets!!.clear()
            return
        }
        multiDrawCounts?.let { MemoryUtil.memFree(it) }
        multiDrawOffsets?.let { MemoryUtil.memFree(it) }
        val capacity = maxOf(rangeCount, 64)
        multiDrawCounts = MemoryUtil.memAllocInt(capacity)
        multiDrawOffsets = MemoryUtil.memAllocPointer(capacity)
        multiDrawCapacity = capacity
    }

    fun renderWireframe() {
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBindVertexArray(vaoId)
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    }

    fun renderLines() {
        glBindVertexArray(vaoId)
        glDrawElements(GL_LINES, vertexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    fun cleanup() {
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        vboIds.forEach { glDeleteBuffers(it) }
        glBindVertexArray(0)
        glDeleteVertexArrays(vaoId)
        multiDrawCounts?.let { MemoryUtil.memFree(it) }
        multiDrawCounts = null
        multiDrawOffsets?.let { MemoryUtil.memFree(it) }
        multiDrawOffsets = null
        multiDrawCapacity = 0
    }

    companion object {
        fun createWithAlpha(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            alphas: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                alphas = alphas
            )
        )

        fun createWithAtlasTransform(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            atlasTransforms: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                atlasTransforms = atlasTransforms
            )
        )

        fun createWithAtlasAndAlpha(
            positions: FloatArray,
            colors: FloatArray,
            textureCoords: FloatArray,
            normals: FloatArray,
            alphas: FloatArray,
            atlasTransforms: FloatArray,
            indices: IntArray
        ): Mesh = Mesh(
            MeshBuffers.pack(
                positions = positions,
                colors = colors,
                texCoords = textureCoords,
                normals = normals,
                indices = indices,
                alphas = alphas,
                atlasTransforms = atlasTransforms
            )
        )

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
                -s, -s, s,  s, -s, s,  s, s, s,  -s, s, s,
                -s, -s, -s,  -s, s, -s,  s, s, -s,  s, -s, -s,
                -s, s, -s,  -s, s, s,  s, s, s,  s, s, -s,
                -s, -s, -s,  s, -s, -s,  s, -s, s,  -s, -s, s,
                s, -s, -s,  s, s, -s,  s, s, s,  s, -s, s,
                -s, -s, -s,  -s, -s, s,  -s, s, s,  -s, s, -s
            )
            val colors = FloatArray(positions.size) { 0.7f }
            val indices = intArrayOf(
                0, 1, 2, 0, 2, 3,
                4, 5, 6, 4, 6, 7,
                8, 9, 10, 8, 10, 11,
                12, 13, 14, 12, 14, 15,
                16, 17, 18, 16, 18, 19,
                20, 21, 22, 20, 22, 23
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
                positions.addAll(listOf(pos, 0f, -halfSize))
                positions.addAll(listOf(pos, 0f, halfSize))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                colors.addAll(listOf(0.5f, 0.5f, 0.5f))
                normals.addAll(listOf(0f, 1f, 0f))
                normals.addAll(listOf(0f, 1f, 0f))
                indices.add(index++)
                indices.add(index++)

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
