package org.darkan.tools.cache.texture

import org.darkan.tools.cache.texture.ops.TextureOpPerlinNoise
import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Loads textures from the cache using the full RS procedural texture system:
 * 1. TextureTypes (TEXTURES index) define texture operation DAGs
 * 2. TextureOperations process and combine inputs to generate pixels
 * 3. TextureOpSprite operations reference sprites from SPRITES index
 */
object TextureLoader {
    private val textureCache = mutableMapOf<Int, Int>() // textureId -> GL texture ID
    private val spriteDataCache = mutableMapOf<Int, SpriteData?>() // spriteId -> SpriteData
    private val textureTypeCache = mutableMapOf<Int, TextureType?>() // textureId -> TextureType
    private var defaultTextureId: Int = 0
    private var cacheRef: Cache? = null

    /** Default texture size for procedural generation */
    private const val TEXTURE_SIZE = 128

    fun init(cache: Cache) {
        cacheRef = cache
        defaultTextureId = createCheckerTexture()
        TextureRaster.calculateTable()
    }

    /**
     * Get GL texture for a material/texture ID from model faces.
     * Uses the full procedural texture system to generate pixels.
     *
     * @param cache The game cache
     * @param textureId The texture/material ID from faceTextures
     * @return OpenGL texture ID
     */
    fun getTextureForMaterial(cache: Cache, textureId: Int): Int {
        if (textureId < 0) return defaultTextureId

        return textureCache.getOrPut(textureId) {
            // Try to load and generate the texture using the full operation pipeline
            val textureType = loadTextureType(cache, textureId)
            if (textureType != null) {
                val pixels = textureType.getPixelsArgb(
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    spriteLoader = { spriteId ->
                        loadSpriteData(cache, spriteId)
                    },
                    textureLoader = { materialId, width, height ->
                        generateTexturePixels(cache, materialId, width, height)
                    }
                )

                return@getOrPut createTextureFromArgb(pixels, TEXTURE_SIZE, TEXTURE_SIZE)
            }

            // Fallback: try loading texture ID directly as sprite ID (some models use this)
            val directSprite = loadSpriteAsTexture(cache, textureId)
            if (directSprite != null) {
                return@getOrPut directSprite
            }

            defaultTextureId
        }
    }

    /**
     * Generate raw ARGB pixels for a texture (used for nested texture references).
     * This is separate from getTextureForMaterial to avoid circular GL texture creation.
     */
    private val pixelCache = mutableMapOf<Int, IntArray?>()

    private fun generateTexturePixels(cache: Cache, textureId: Int, width: Int, height: Int): IntArray? {
        // Use a simple cache to avoid redundant generation
        return pixelCache.getOrPut(textureId) {
            val textureType = loadTextureType(cache, textureId) ?: return@getOrPut null
            textureType.getPixelsArgb(
                width,
                height,
                spriteLoader = { spriteId ->
                    loadSpriteData(cache, spriteId)
                },
                textureLoader = { materialId, w, h ->
                    // Recursive call for deeply nested textures
                    if (materialId != textureId) { // Avoid infinite recursion
                        generateTexturePixels(cache, materialId, w, h)
                    } else null
                }
            )
        }
    }

    /**
     * Load a TextureType from the TEXTURES index.
     */
    private fun loadTextureType(cache: Cache, textureId: Int): TextureType? {
        return textureTypeCache.getOrPut(textureId) {
            val data = cache.data(Index.TEXTURES, textureId) ?: return@getOrPut null
            if (data.isEmpty()) return@getOrPut null
            try {
                val textureType = TextureType(data)

                // Debug output for texture 928
                if (textureId == 928) {
                    debugTexture928(textureType)
                }

                textureType
            } catch (e: Exception) {
                // Invalid texture data - return null
                null
            }
        }
    }

    /**
     * Debug helper to print texture 928's operation graph and Perlin noise parameters.
     */
    private fun debugTexture928(textureType: TextureType) {
        val opNames = textureType.textureOperations.map { it::class.simpleName ?: "Unknown" }
        System.err.println("[Texture 928] Operations: $opNames")

        for (op in textureType.textureOperations) {
            if (op is TextureOpPerlinNoise) {
                // Access private fields via reflection
                val scaleXField = TextureOpPerlinNoise::class.java.getDeclaredField("texCoordScaleX")
                val scaleYField = TextureOpPerlinNoise::class.java.getDeclaredField("texCoordScaleY")
                scaleXField.isAccessible = true
                scaleYField.isAccessible = true
                val scaleX = scaleXField.getInt(op)
                val scaleY = scaleYField.getInt(op)
                System.err.println("[Texture 928] PerlinNoise: texCoordScaleX=$scaleX, texCoordScaleY=$scaleY")
            }
        }
    }

    /**
     * Load sprite data for use by TextureOpSprite operations.
     */
    private fun loadSpriteData(cache: Cache, spriteId: Int): SpriteData? {
        return spriteDataCache.getOrPut(spriteId) {
            val data = cache.data(Index.SPRITES, spriteId) ?: return@getOrPut null
            decodeSpriteToData(data, 0)
        }
    }

    /**
     * Load a sprite directly as a GL texture (fallback for simple textures).
     */
    private fun loadSpriteAsTexture(cache: Cache, spriteId: Int): Int? {
        val spriteData = loadSpriteData(cache, spriteId) ?: return null
        return createTextureFromArgb(spriteData.pixels, spriteData.width, spriteData.height)
    }

    /**
     * Decode sprite data to SpriteData for use by texture operations.
     */
    private fun decodeSpriteToData(data: ByteArray, subIndex: Int): SpriteData? {
        if (data.size < 10) return null

        val buffer = BufferReader(data)

        // Read sprite count from end (unsigned short)
        buffer.position(data.size - 2)
        val spriteCount = buffer.readShort() and 0xFFFF
        if (spriteCount == 0 || subIndex >= spriteCount) return null

        // Read header
        buffer.position(data.size - 7 - spriteCount * 8)
        val biggestWidth = buffer.readShort()
        val biggestHeight = buffer.readShort()
        val paletteSize = buffer.readUnsignedByte() + 1

        // Read sprite dimensions
        val minX = IntArray(spriteCount)
        val minY = IntArray(spriteCount)
        val widths = IntArray(spriteCount)
        val heights = IntArray(spriteCount)

        for (i in 0 until spriteCount) minX[i] = buffer.readShort()
        for (i in 0 until spriteCount) minY[i] = buffer.readShort()
        for (i in 0 until spriteCount) widths[i] = buffer.readShort()
        for (i in 0 until spriteCount) heights[i] = buffer.readShort()

        val innerWidth = widths[subIndex]
        val innerHeight = heights[subIndex]
        val offsetX = minX[subIndex]
        val offsetY = minY[subIndex]
        if (innerWidth <= 0 || innerHeight <= 0) return null

        // Calculate full canvas size (like method1523 does)
        // biggestWidth and biggestHeight define the full canvas
        // Fall back to inner dimensions if biggest is invalid
        val fullWidth = if (biggestWidth > 0) biggestWidth else innerWidth
        val fullHeight = if (biggestHeight > 0) biggestHeight else innerHeight

        // Read palette
        buffer.position(data.size - 7 - spriteCount * 8 - (paletteSize - 1) * 3)
        val palette = IntArray(paletteSize)
        for (i in 1 until paletteSize) {
            palette[i] = buffer.readUnsignedMedium()
            if (palette[i] == 0) palette[i] = 1
        }

        // Read pixel data
        buffer.position(0)
        var raster: ByteArray? = null
        var alpha: ByteArray? = null

        for (i in 0 until spriteCount) {
            val area = widths[i] * heights[i]
            val flags = buffer.readUnsignedByte()
            val hasAlpha = (flags and 0x2) != 0
            val columnMajor = (flags and 0x1) != 0

            val spriteRaster = ByteArray(area)
            val spriteAlpha = if (hasAlpha) ByteArray(area) else null

            if (!columnMajor) {
                for (p in 0 until area) {
                    spriteRaster[p] = buffer.readByte().toByte()
                }
                if (hasAlpha) {
                    for (p in 0 until area) {
                        spriteAlpha!![p] = buffer.readByte().toByte()
                    }
                }
            } else {
                for (x in 0 until widths[i]) {
                    for (y in 0 until heights[i]) {
                        spriteRaster[x + y * widths[i]] = buffer.readByte().toByte()
                    }
                }
                if (hasAlpha) {
                    for (x in 0 until widths[i]) {
                        for (y in 0 until heights[i]) {
                            spriteAlpha!![x + y * widths[i]] = buffer.readByte().toByte()
                        }
                    }
                }
            }

            if (i == subIndex) {
                raster = spriteRaster
                alpha = spriteAlpha
            }
        }

        if (raster == null) return null

        // Convert indexed to ARGB on the full canvas (like method1523 does)
        // This expands the sprite to its full bounding box
        val pixels = IntArray(fullWidth * fullHeight)
        for (y in 0 until innerHeight) {
            val srcRowStart = y * innerWidth
            val destRowStart = (y + offsetY) * fullWidth + offsetX
            for (x in 0 until innerWidth) {
                val srcIdx = srcRowStart + x
                val destIdx = destRowStart + x
                if (destIdx < pixels.size) {
                    val paletteIdx = raster[srcIdx].toInt() and 0xFF
                    val rgb = palette[paletteIdx]
                    val a = if (alpha != null) {
                        alpha[srcIdx].toInt() and 0xFF
                    } else {
                        if (paletteIdx == 0) 0 else 255
                    }
                    // ARGB format
                    pixels[destIdx] = (a shl 24) or rgb
                }
            }
        }

        return SpriteData(pixels, fullWidth, fullHeight)
    }

    /**
     * Create OpenGL texture from ARGB pixels.
     */
    private fun createTextureFromArgb(pixels: IntArray, width: Int, height: Int): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        // Enable anisotropic filtering for better texture quality at angles
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            // Flip texture vertically for OpenGL (OpenGL V=0 at bottom, textures have row 0 at top)
            for (y in (height - 1) downTo 0) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                    buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
                    buffer.put((pixel and 0xFF).toByte())          // B
                    buffer.put(((pixel shr 24) and 0xFF).toByte()) // A
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
     * Create a checkerboard placeholder texture.
     */
    private fun createCheckerTexture(): Int {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val checker = ((x / 8) + (y / 8)) % 2 == 0
                val gray = if (checker) 200 else 100
                pixels[y * size + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return createTextureFromArgb(pixels, size, size)
    }

    fun bindTexture(textureId: Int, unit: Int = 0) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, textureId)
    }

    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun clearCache() {
        textureCache.values.forEach { glDeleteTextures(it) }
        textureCache.clear()
        spriteDataCache.clear()
        textureTypeCache.clear()
        pixelCache.clear()
        if (defaultTextureId != 0) {
            glDeleteTextures(defaultTextureId)
            defaultTextureId = 0
        }
    }

}
