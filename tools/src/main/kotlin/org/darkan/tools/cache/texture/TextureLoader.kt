package org.darkan.tools.cache.texture

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder

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

    /** Material definitions loaded from the cache, indexed by material ID */
    private var materialDefinitions: Array<MaterialDefinition> = emptyArray()

    /** Default texture size for procedural generation (128 for normal, 64 for small) */
    private const val TEXTURE_SIZE_NORMAL = 128
    private const val TEXTURE_SIZE_SMALL = 64

    fun init(cache: Cache) {
        cacheRef = cache
        defaultTextureId = createCheckerTexture()
        TextureRaster.calculateTable()

        // Load all material definitions once at init time
        materialDefinitions = MaterialDecoder().load(cache)
    }

    /**
     * Get the MaterialDefinition for a given material ID, or null if not found.
     */
    private fun getMaterial(materialId: Int): MaterialDefinition? {
        if (materialId < 0 || materialId >= materialDefinitions.size) return null
        return materialDefinitions[materialId]
    }

    /**
     * Determine if a material should use the opaque pixel path.
     * Matches the RS client logic:
     *   effectCombiner != 2 && effectId != 1 && effectId != 7
     *
     * If no material definition exists, defaults to opaque (most materials are opaque).
     */
    private fun isMaterialOpaque(material: MaterialDefinition?): Boolean {
        if (material == null) return true
        val effectId = material.type.toInt()
        val effectCombiner = material.anInt1226
        return effectCombiner != 2 && effectId != 1 && effectId != 7
    }

    /**
     * Public query: does the given material render as fully opaque?
     *
     * This answers the RENDERING question (opaque pass vs transparent pass),
     * not the texture GENERATION question (getPixelsRgb vs getPixelsArgb).
     *
     * After alpha post-processing:
     *   - Standard opaque materials (not effectId 1/7, not effectCombiner 2):
     *     all pixels get alpha=255 via isMaterialOpaque → opaque path → true
     *   - effectCombiner=0 with constAlpha=255: all pixels get alpha=255 → true
     *   - effectCombiner=0 with constAlpha<255: all pixels get partial alpha → false
     *   - effectCombiner=1 (binary alpha): some pixels alpha=0 → false
     *   - effectCombiner=2 (per-pixel alpha): variable alpha → false
     */
    fun isOpaque(textureId: Int): Boolean {
        if (textureId < 0) return true
        val material = getMaterial(textureId) ?: return true
        val effectId = material.type.toInt()
        val effectCombiner = material.anInt1226

        // Standard opaque materials (not effectId 1/7, not effectCombiner 2)
        if (effectCombiner != 2 && effectId != 1 && effectId != 7) return true

        // For effectCombiner 0: check if constant alpha makes it opaque
        if (effectCombiner == 0) {
            val rawAlpha = material.aByte1225.toInt() and 0xFF
            val constAlpha = if (rawAlpha == 0) 255 else rawAlpha
            return constAlpha == 255
        }

        // effectCombiner 1 (binary) or 2 (per-pixel): needs transparency handling
        return false
    }

    /**
     * Check if a material's faces should be skipped (not rendered).
     * Matches the RS client's ModelGL behavior where faces with
     * MaterialType.skipTriangles == true are excluded from rendering.
     *
     * @param materialId The material/texture ID from faceTextures
     * @return true if the material's faces should not be rendered
     */
    fun shouldSkipMaterial(materialId: Int): Boolean {
        if (materialId < 0) return false
        return getMaterial(materialId)?.skipTriangles ?: false
    }

    /**
     * Get the texture size for a material based on its isSmall flag.
     * true (isSmall) -> 64x64, false -> 128x128.
     * Defaults to 128 if no material definition exists.
     */
    private fun getTextureSize(material: MaterialDefinition?): Int {
        if (material == null) return TEXTURE_SIZE_NORMAL
        return if (material.aBoolean1204) TEXTURE_SIZE_SMALL else TEXTURE_SIZE_NORMAL
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
                val material = getMaterial(textureId)
                val textureSize = getTextureSize(material)
                val opaque = isMaterialOpaque(material)

                val pixels = if (opaque) {
                    // Opaque path: alpha=255 for non-black, alpha=0 for black.
                    // Does NOT evaluate the opacity operation.
                    textureType.getPixelsRgb(
                        textureSize,
                        textureSize,
                        spriteLoader = { spriteId ->
                            loadSpriteData(cache, spriteId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7
                    )
                } else {
                    // Transparent path: alpha from the opacity operation.
                    textureType.getPixelsArgb(
                        textureSize,
                        textureSize,
                        spriteLoader = { spriteId ->
                            loadSpriteData(cache, spriteId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7
                    )
                }

                // Post-process alpha based on effectCombiner.
                // In the RS client, effectCombiner controls how alpha is actually used:
                //   effectCombiner=2: per-pixel alpha from opacity operation (keep as-is)
                //   effectCombiner=0: constant alpha applied to ALL pixels (including black).
                //     The RS client's software rasterizer uses constant alpha for every texel,
                //     and its GL path uses BlendMode.NONE, so black pixels render as black,
                //     not as holes. Our shader discards alpha<0.01, so we must set alpha on
                //     all pixels to prevent black texels from being discarded.
                //   effectCombiner=1: binary alpha (0 for black/transparent, 255 for non-black).
                //     This creates hard-edged transparency masks (e.g., foliage, fences).
                if (material != null && material.anInt1226 != 2) {
                    val effectCombiner = material.anInt1226
                    if (effectCombiner == 0) {
                        // Constant alpha for ALL pixels (including black).
                        // The client renders all pixels; black shows as black, not transparent.
                        val rawAlpha = material.aByte1225.toInt() and 0xFF
                        val constAlpha = if (rawAlpha == 0) 255 else rawAlpha
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                        }
                    } else {
                        // effectCombiner=1 (binary) or other: alpha is 0 for black, 255 for non-black
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            val rgb = px and 0x00FFFFFF
                            pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                        }
                    }
                }

                return@getOrPut createTextureFromArgb(pixels, textureSize, textureSize)
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
     * Generate raw pixels for a texture (used for nested texture references).
     * The client uses materialProvider.getPixels(materialId, 1.0f, size, size, false)
     * for nested TextureOpTexture references, which is the RGB (opaque) path with
     * brightness=1.0. This is separate from getTextureForMaterial to avoid circular
     * GL texture creation.
     */
    private val pixelCache = mutableMapOf<Int, IntArray?>()

    private fun generateTexturePixels(cache: Cache, textureId: Int, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int): IntArray? {
        // Use a simple cache to avoid redundant generation
        return pixelCache.getOrPut(textureId) {
            val textureType = loadTextureType(cache, textureId) ?: return@getOrPut null

            // In the RS client, TextureOpTexture.createImageCache() loads nested textures
            // at the NESTED material's own size (based on isSmall), NOT the parent's.
            // We must do the same here to avoid array bounds mismatches.
            val nestedMaterial = getMaterial(textureId)
            val nestedSize = getTextureSize(nestedMaterial)

            // Nested textures always use the RGB (opaque) path with brightness=1.0
            val result = textureType.getPixelsRgb(
                nestedSize,
                nestedSize,
                spriteLoader = { spriteId ->
                    loadSpriteData(cache, spriteId)
                },
                textureLoader = { materialId, w, h ->
                    // Recursive call for deeply nested textures
                    if (materialId != textureId) { // Avoid infinite recursion
                        generateTexturePixels(cache, materialId, w, h)
                    } else null
                },
                brightness = 1.0
            )

            result
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
                TextureType(data)
            } catch (e: Exception) {
                // Invalid texture data - return null
                null
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

    /**
     * Public access to sprite decoding for diagnostic tools.
     */
    fun decodeSpriteToDataPublic(data: ByteArray, subIndex: Int): SpriteData? {
        return decodeSpriteToData(data, subIndex)
    }

    /**
     * Debug: Dump the generated texture pixels to a PPM image file for visual inspection.
     * The image is saved WITHOUT vertical flip (as generated, row 0 = top).
     */
    fun dumpTextureImage(cache: Cache, materialId: Int, outputPath: String) {
        val textureType = loadTextureType(cache, materialId)
        if (textureType == null) {
            println("dumpTextureImage: Could not load texture type for material $materialId")
            return
        }
        val material = getMaterial(materialId)
        val textureSize = getTextureSize(material)
        val opaque = isMaterialOpaque(material)

        val pixels = if (opaque) {
            textureType.getPixelsRgb(
                textureSize, textureSize,
                spriteLoader = { spriteId -> loadSpriteData(cache, spriteId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        } else {
            textureType.getPixelsArgb(
                textureSize, textureSize,
                spriteLoader = { spriteId -> loadSpriteData(cache, spriteId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        }

        // Apply same alpha post-processing as getTextureForMaterial
        if (material != null && material.anInt1226 != 2) {
            val effectCombiner = material.anInt1226
            if (effectCombiner == 0) {
                val rawAlpha = material.aByte1225.toInt() and 0xFF
                val constAlpha = if (rawAlpha == 0) 255 else rawAlpha
                for (i in pixels.indices) {
                    val px = pixels[i]
                    pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                }
            } else {
                for (i in pixels.indices) {
                    val px = pixels[i]
                    val rgb = px and 0x00FFFFFF
                    pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                }
            }
        }

        // Write as PPM (P3 format) - shows RGB, ignoring alpha
        // Also write a separate file with alpha channel visualized
        val sb = StringBuilder()
        sb.appendLine("P3")
        sb.appendLine("$textureSize $textureSize")
        sb.appendLine("255")
        for (y in 0 until textureSize) {
            for (x in 0 until textureSize) {
                val pixel = pixels[y * textureSize + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sb.append("$r $g $b ")
            }
            sb.appendLine()
        }
        java.io.File(outputPath).writeText(sb.toString())

        // Also write alpha channel as grayscale
        val sbAlpha = StringBuilder()
        sbAlpha.appendLine("P2")
        sbAlpha.appendLine("$textureSize $textureSize")
        sbAlpha.appendLine("255")
        for (y in 0 until textureSize) {
            for (x in 0 until textureSize) {
                val pixel = pixels[y * textureSize + x]
                val a = (pixel shr 24) and 0xFF
                sbAlpha.append("$a ")
            }
            sbAlpha.appendLine()
        }
        java.io.File(outputPath.replace(".ppm", "_alpha.ppm")).writeText(sbAlpha.toString())

        // Print stats
        var nonBlack = 0
        var transparent = 0
        for (pixel in pixels) {
            val rgb = pixel and 0x00FFFFFF
            val a = (pixel shr 24) and 0xFF
            if (rgb != 0) nonBlack++
            if (a == 0) transparent++
        }
        println("dumpTextureImage($materialId): ${textureSize}x${textureSize}, opaque=$opaque, effectCombiner=${material?.anInt1226}, nonBlack=$nonBlack/${pixels.size}, transparent=$transparent/${pixels.size}")
        println("  Saved to $outputPath")
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
