package org.darkan.tools.engine.render

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VertexAttribute(
    val location: Int,
    val components: Int,
    val type: Int,
    val normalized: Boolean,
    val byteOffset: Int
)

class MeshBuffers private constructor(
    val vertexCount: Int,
    val indexCount: Int,
    val vertexData: ByteBuffer,
    val attributes: List<VertexAttribute>,
    val indices: IntBuffer
) {
    private val freed = AtomicBoolean(false)

    val isFreed: Boolean get() = freed.get()

    fun free() {
        if (!freed.compareAndSet(false, true)) return
        MemoryUtil.memFree(vertexData)
        MemoryUtil.memFree(indices)
    }

    companion object {
        val packColorBytes: Boolean = System.getProperty("darkan.byteColors") != "false"

        val packedBytes = AtomicLong(0)

        val packNanos = AtomicLong(0)

        fun pack(
            positions: FloatArray,
            colors: FloatArray? = null,
            texCoords: FloatArray? = null,
            normals: FloatArray? = null,
            indices: IntArray,
            alphas: FloatArray? = null,
            atlasTransforms: FloatArray? = null,
            colorComponents: Int = 3
        ): MeshBuffers {
            val packStart = System.nanoTime()
            val colorVertices = if (colors != null) colors.size / colorComponents else 0
            val quantiseColors = packColorBytes && colors != null

            var totalBytes = positions.size * Float.SIZE_BYTES
            if (colors != null) {
                totalBytes += if (quantiseColors) colorVertices * COLOR_BYTES
                else colors.size * Float.SIZE_BYTES
            }
            if (texCoords != null) totalBytes += texCoords.size * Float.SIZE_BYTES
            if (normals != null) totalBytes += normals.size * Float.SIZE_BYTES
            if (alphas != null) totalBytes += alphas.size * Float.SIZE_BYTES
            if (atlasTransforms != null) totalBytes += atlasTransforms.size * Float.SIZE_BYTES

            val vertexData = MemoryUtil.memAlloc(totalBytes)
            val baseAddress = MemoryUtil.memAddress(vertexData)
            val floatView = MemoryUtil.memFloatBuffer(baseAddress, totalBytes / Float.SIZE_BYTES)
            val attributes = ArrayList<VertexAttribute>(6)
            var offset = 0

            fun append(values: FloatArray?, location: Int, components: Int) {
                if (values == null) return
                floatView.put(offset / Float.SIZE_BYTES, values, 0, values.size)
                attributes.add(VertexAttribute(location, components, GL_FLOAT, false, offset))
                offset += values.size * Float.SIZE_BYTES
            }

            fun appendColors() {
                if (colors == null) return
                if (!quantiseColors) {
                    append(colors, LOCATION_COLOR, colorComponents)
                    return
                }
                writeColorBytes(baseAddress + offset, colors, colorVertices, colorComponents)
                attributes.add(
                    VertexAttribute(LOCATION_COLOR, 4, GL_UNSIGNED_BYTE, true, offset)
                )
                offset += colorVertices * COLOR_BYTES
            }

            append(positions, LOCATION_POSITION, 3)
            appendColors()
            append(texCoords, LOCATION_TEX_COORD, 2)
            append(normals, LOCATION_NORMAL, 3)
            append(alphas, LOCATION_ALPHA, 1)
            append(atlasTransforms, LOCATION_ATLAS_TRANSFORM, 4)

            val packed = MeshBuffers(
                vertexCount = positions.size / 3,
                indexCount = indices.size,
                vertexData = vertexData,
                attributes = attributes,
                indices = MemoryUtil.memAllocInt(indices.size).put(0, indices, 0, indices.size)
            )

            packedBytes.addAndGet(totalBytes.toLong() + indices.size * Int.SIZE_BYTES)
            packNanos.addAndGet(System.nanoTime() - packStart)
            return packed
        }

        internal fun writeColorBytes(
            address: Long,
            colors: FloatArray,
            vertices: Int,
            components: Int
        ) {
            val out = MemoryUtil.memIntBuffer(address, vertices)
            val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            var base = 0
            if (components >= 4) {
                for (i in 0 until vertices) {
                    val r = toByte(colors[base])
                    val g = toByte(colors[base + 1])
                    val b = toByte(colors[base + 2])
                    val a = toByte(colors[base + 3])
                    out.put(
                        i,
                        if (littleEndian) r or (g shl 8) or (b shl 16) or (a shl 24)
                        else (r shl 24) or (g shl 16) or (b shl 8) or a
                    )
                    base += components
                }
            } else {
                val opaque = if (littleEndian) 0xFF shl 24 else 0xFF
                for (i in 0 until vertices) {
                    val r = toByte(colors[base])
                    val g = toByte(colors[base + 1])
                    val b = toByte(colors[base + 2])
                    out.put(
                        i,
                        if (littleEndian) r or (g shl 8) or (b shl 16) or opaque
                        else (r shl 24) or (g shl 16) or (b shl 8) or opaque
                    )
                    base += components
                }
            }
        }

        private fun toByte(value: Float): Int {
            val scaled = (value * 255f + 0.5f).toInt()
            return if (scaled < 0) 0 else if (scaled > 255) 255 else scaled
        }

        internal const val COLOR_BYTES = 4

        const val LOCATION_POSITION = 0
        const val LOCATION_COLOR = 1
        const val LOCATION_TEX_COORD = 2
        const val LOCATION_NORMAL = 3
        const val LOCATION_ALPHA = 4
        const val LOCATION_ATLAS_TRANSFORM = 5
    }
}
