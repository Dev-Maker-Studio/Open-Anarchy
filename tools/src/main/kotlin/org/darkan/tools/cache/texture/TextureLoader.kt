package org.darkan.tools.cache.texture

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.SpriteDecoder
import java.nio.ByteBuffer

/**
 * Loads sprites from the cache and converts them to OpenGL textures.
 */
object TextureLoader {
    private val textureCache = mutableMapOf<Int, Int>()
    private val spriteDecoder = SpriteDecoder()

    fun loadTexture(cache: Cache, spriteId: Int, subImageIndex: Int = 0): Int {
        val cacheKey = spriteId * 1000 + subImageIndex
        return textureCache.getOrPut(cacheKey) {
            createTexture(cache, spriteId, subImageIndex)
        }
    }

    private fun createTexture(cache: Cache, spriteId: Int, subImageIndex: Int): Int {
        val data = cache.data(Index.SPRITES, spriteId) ?: return 0

        // Simple sprite decoding - just create a placeholder for now
        // Full implementation would use SpriteDecoder properly
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        // Create a simple placeholder texture
        val width = 64
        val height = 64
        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val checker = ((x / 8) + (y / 8)) % 2 == 0
                    val color = if (checker) 200.toByte() else 100.toByte()
                    buffer.put(color)
                    buffer.put(color)
                    buffer.put(color)
                    buffer.put(255.toByte())
                }
            }
            buffer.flip()

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }

    /**
     * Create texture from raw RGBA pixel data.
     */
    fun createTextureFromPixels(pixels: IntArray, width: Int, height: Int): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xff).toByte()) // R
                buffer.put(((pixel shr 8) and 0xff).toByte())  // G
                buffer.put((pixel and 0xff).toByte())          // B
                buffer.put(((pixel shr 24) and 0xff).toByte()) // A
            }
            buffer.flip()

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }

    /**
     * Create a solid color texture.
     */
    fun createSolidTexture(r: Int, g: Int, b: Int, a: Int = 255): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)

        val buffer = MemoryUtil.memAlloc(4)
        try {
            buffer.put(r.toByte())
            buffer.put(g.toByte())
            buffer.put(b.toByte())
            buffer.put(a.toByte())
            buffer.flip()

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }

    fun bindTexture(textureId: Int, unit: Int = 0) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, textureId)
    }

    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun deleteTexture(textureId: Int) {
        glDeleteTextures(textureId)
    }

    fun clearCache() {
        textureCache.values.forEach { glDeleteTextures(it) }
        textureCache.clear()
    }
}
