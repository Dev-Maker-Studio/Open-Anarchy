package org.darkan.tools.engine.render

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Represents a region within a texture atlas.
 * Contains UV coordinates for transforming mesh UVs to atlas space.
 */
data class AtlasRegion(
    val atlasPageIndex: Int,
    val u0: Float,  // Left edge in atlas [0, 1]
    val v0: Float,  // Top edge in atlas [0, 1]
    val u1: Float,  // Right edge in atlas [0, 1]
    val v1: Float,  // Bottom edge in atlas [0, 1]
    val originalWidth: Int,
    val originalHeight: Int
) {
    /** Width of the region in atlas UV space */
    val uScale: Float get() = u1 - u0

    /** Height of the region in atlas UV space */
    val vScale: Float get() = v1 - v0

    /**
     * Transform a local UV coordinate to atlas UV coordinate.
     */
    fun transformUV(localU: Float, localV: Float): Pair<Float, Float> {
        return Pair(
            u0 + localU * uScale,
            v0 + localV * vScale
        )
    }

    /**
     * Get the atlas transform as a vec4 for shader use.
     * (u0, v0, uScale, vScale)
     */
    fun getTransformVec4(): FloatArray {
        return floatArrayOf(u0, v0, uScale, vScale)
    }
}

/**
 * Atlas page containing packed textures.
 */
private class AtlasPage(
    val size: Int,
    val textureSize: Int
) {
    val glTextureId: Int = glGenTextures()
    val pixels: IntArray = IntArray(size * size)

    // Packing state - simple row-based packing
    private var currentRow = 0
    private var currentColumn = 0
    private var rowHeight = textureSize

    // Textures per row
    private val texturesPerRow = size / textureSize

    init {
        // Initialize with transparent black
        pixels.fill(0)

        // Create the GL texture
        glBindTexture(GL_TEXTURE_2D, glTextureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        // Enable anisotropic filtering if available
        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        // Allocate the texture
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * Try to add a texture to this page.
     *
     * @return The region if successful, null if no space
     */
    fun addTexture(texturePixels: IntArray, width: Int, height: Int): AtlasRegion? {
        // Check if we have space in current row
        if (currentColumn + 1 > texturesPerRow) {
            // Move to next row
            currentRow++
            currentColumn = 0
        }

        // Check if we have space for another row
        if (currentRow >= texturesPerRow) {
            return null
        }

        // Calculate position in atlas
        val x = currentColumn * textureSize
        val y = currentRow * textureSize

        // Copy pixels to atlas
        copyPixels(texturePixels, width, height, x, y)

        // Calculate UV coordinates
        val u0 = x.toFloat() / size
        val v0 = y.toFloat() / size
        val u1 = (x + textureSize).toFloat() / size
        val v1 = (y + textureSize).toFloat() / size

        currentColumn++

        return AtlasRegion(
            atlasPageIndex = -1, // Will be set by TextureAtlas
            u0 = u0, v0 = v0,
            u1 = u1, v1 = v1,
            originalWidth = width,
            originalHeight = height
        )
    }

    /**
     * Copy texture pixels into the atlas at the specified position.
     * Handles scaling if the source is smaller than textureSize.
     */
    private fun copyPixels(src: IntArray, srcWidth: Int, srcHeight: Int, destX: Int, destY: Int) {
        // For now, assume source is exactly textureSize x textureSize or smaller
        // Scale up if smaller using nearest neighbor
        val scaleX = srcWidth.toFloat() / textureSize
        val scaleY = srcHeight.toFloat() / textureSize

        for (dy in 0 until textureSize) {
            for (dx in 0 until textureSize) {
                val sx = (dx * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val sy = (dy * scaleY).toInt().coerceIn(0, srcHeight - 1)

                val srcIdx = sy * srcWidth + sx
                val destIdx = (destY + dy) * size + (destX + dx)

                if (srcIdx < src.size && destIdx < pixels.size) {
                    pixels[destIdx] = src[srcIdx]
                }
            }
        }
    }

    /**
     * Upload the atlas to GPU.
     */
    fun upload() {
        val buffer = MemoryUtil.memAlloc(size * size * 4)
        try {
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
                buffer.put((pixel and 0xFF).toByte())          // B
                buffer.put(((pixel shr 24) and 0xFF).toByte()) // A
            }
            buffer.flip()

            glBindTexture(GL_TEXTURE_2D, glTextureId)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
            glBindTexture(GL_TEXTURE_2D, 0)
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    fun cleanup() {
        glDeleteTextures(glTextureId)
    }

    fun getRemainingSlots(): Int {
        val totalSlots = texturesPerRow * texturesPerRow
        val usedSlots = currentRow * texturesPerRow + currentColumn
        return totalSlots - usedSlots
    }
}

/**
 * Texture atlas for reducing texture bind calls.
 *
 * Packs multiple textures into large atlas pages. Each texture gets
 * a region with UV transformation data for shader use.
 *
 * @param atlasSize Size of each atlas page (e.g., 4096 for 4096x4096)
 * @param textureSize Size of individual textures (e.g., 128 for 128x128)
 */
class TextureAtlas(
    private val atlasSize: Int = 4096,
    private val textureSize: Int = 128
) {
    private val pages = mutableListOf<AtlasPage>()
    private val regions = mutableMapOf<Int, AtlasRegion>()

    // Textures per page = (atlasSize / textureSize)^2
    val texturesPerPage: Int = (atlasSize / textureSize) * (atlasSize / textureSize)

    /**
     * Add a texture to the atlas.
     *
     * @param textureId The original texture ID
     * @param pixels ARGB pixel data
     * @param width Texture width
     * @param height Texture height
     * @return The atlas region for this texture
     */
    fun addTexture(textureId: Int, pixels: IntArray, width: Int = textureSize, height: Int = textureSize): AtlasRegion {
        // Check if already added
        regions[textureId]?.let { return it }

        // Find a page with space or create new
        var region: AtlasRegion? = null
        var pageIndex = 0

        for ((idx, page) in pages.withIndex()) {
            region = page.addTexture(pixels, width, height)
            if (region != null) {
                pageIndex = idx
                break
            }
        }

        // No space in existing pages, create new
        if (region == null) {
            val newPage = AtlasPage(atlasSize, textureSize)
            pages.add(newPage)
            pageIndex = pages.size - 1
            region = newPage.addTexture(pixels, width, height)!!
        }

        // Update with correct page index
        val finalRegion = region.copy(atlasPageIndex = pageIndex)
        regions[textureId] = finalRegion

        return finalRegion
    }

    /**
     * Get the atlas region for a texture.
     */
    fun getRegion(textureId: Int): AtlasRegion? = regions[textureId]

    /**
     * Check if a texture is in the atlas.
     */
    fun hasTexture(textureId: Int): Boolean = regions.containsKey(textureId)

    /**
     * Upload all atlas pages to GPU.
     * Call this after adding all textures.
     */
    fun upload() {
        for (page in pages) {
            page.upload()
        }
    }

    /**
     * Bind atlas pages to texture units starting at startUnit.
     */
    fun bindPages(startUnit: Int = 0) {
        for ((idx, page) in pages.withIndex()) {
            glActiveTexture(GL_TEXTURE0 + startUnit + idx)
            glBindTexture(GL_TEXTURE_2D, page.glTextureId)
        }
    }

    /**
     * Bind a specific atlas page.
     */
    fun bindPage(pageIndex: Int, unit: Int = 0) {
        if (pageIndex in pages.indices) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, pages[pageIndex].glTextureId)
        }
    }

    /**
     * Get the GL texture ID for a page.
     */
    fun getPageTextureId(pageIndex: Int): Int? {
        return pages.getOrNull(pageIndex)?.glTextureId
    }

    /**
     * Get number of atlas pages.
     */
    fun getPageCount(): Int = pages.size

    /**
     * Get total number of textures in the atlas.
     */
    fun getTextureCount(): Int = regions.size

    /**
     * Get statistics about atlas utilization.
     */
    fun getStats(): AtlasStats {
        val totalSlots = pages.size * texturesPerPage
        val usedSlots = regions.size
        val remainingSlots = pages.sumOf { it.getRemainingSlots() }

        return AtlasStats(
            pageCount = pages.size,
            textureCount = regions.size,
            totalSlots = totalSlots,
            usedSlots = usedSlots,
            utilization = if (totalSlots > 0) usedSlots.toFloat() / totalSlots else 0f
        )
    }

    /**
     * Clean up all GPU resources.
     */
    fun cleanup() {
        for (page in pages) {
            page.cleanup()
        }
        pages.clear()
        regions.clear()
    }

    /**
     * Clear all textures but keep the atlas structure.
     */
    fun clear() {
        cleanup()
    }
}

data class AtlasStats(
    val pageCount: Int,
    val textureCount: Int,
    val totalSlots: Int,
    val usedSlots: Int,
    val utilization: Float
)
