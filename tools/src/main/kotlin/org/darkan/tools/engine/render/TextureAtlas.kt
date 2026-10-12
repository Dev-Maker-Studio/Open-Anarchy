package org.darkan.tools.engine.render

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

data class AtlasRegion(
    val atlasPageIndex: Int,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    val originalWidth: Int,
    val originalHeight: Int
) {
    val uScale: Float get() = u1 - u0

    val vScale: Float get() = v1 - v0

    fun transformUV(localU: Float, localV: Float): Pair<Float, Float> {
        return Pair(
            u0 + localU * uScale,
            v0 + localV * vScale
        )
    }

    fun getTransformVec4(): FloatArray {
        return floatArrayOf(u0, v0, uScale, vScale)
    }
}

private class AtlasPage(
    val size: Int,
    val textureSize: Int,
    val border: Int = 0
) {
    val glTextureId: Int = glGenTextures()
    val pixels: IntArray = IntArray(size * size)

    private val slotSize = textureSize + 2 * border

    private var currentRow = 0
    private var currentColumn = 0

    val texturesPerRow = size / slotSize

    init {
        pixels.fill(0)

        glBindTexture(GL_TEXTURE_2D, glTextureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun addTexture(texturePixels: IntArray, width: Int, height: Int): AtlasRegion? {
        if (currentColumn + 1 > texturesPerRow) {
            currentRow++
            currentColumn = 0
        }

        if (currentRow >= texturesPerRow) {
            return null
        }

        val slotX = currentColumn * slotSize
        val slotY = currentRow * slotSize

        copyPixels(texturePixels, width, height, slotX + border, slotY + border)

        if (border > 0) {
            fillBorderPixels(texturePixels, width, height, slotX, slotY)
        }

        val innerX = slotX + border
        val innerY = slotY + border
        val u0 = innerX.toFloat() / size
        val v0 = innerY.toFloat() / size
        val u1 = (innerX + textureSize).toFloat() / size
        val v1 = (innerY + textureSize).toFloat() / size

        currentColumn++

        return AtlasRegion(
            atlasPageIndex = -1,
            u0 = u0, v0 = v0,
            u1 = u1, v1 = v1,
            originalWidth = width,
            originalHeight = height
        )
    }

    private fun copyPixels(src: IntArray, srcWidth: Int, srcHeight: Int, destX: Int, destY: Int) {
        val scaleX = srcWidth.toFloat() / textureSize
        val scaleY = srcHeight.toFloat() / textureSize

        for (dy in 0 until textureSize) {
            for (dx in 0 until textureSize) {
                val sx = (dx * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val sy = ((textureSize - 1 - dy) * scaleY).toInt().coerceIn(0, srcHeight - 1)

                val srcIdx = sy * srcWidth + sx
                val destIdx = (destY + dy) * size + (destX + dx)

                if (srcIdx < src.size && destIdx < pixels.size) {
                    pixels[destIdx] = src[srcIdx]
                }
            }
        }
    }

    private fun fillBorderPixels(src: IntArray, srcWidth: Int, srcHeight: Int, slotX: Int, slotY: Int) {
        val scaleX = srcWidth.toFloat() / textureSize
        val scaleY = srcHeight.toFloat() / textureSize

        fun sampleWrapped(tx: Int, ty: Int): Int {
            val wrappedTx = ((tx % textureSize) + textureSize) % textureSize
            val wrappedTy = ((ty % textureSize) + textureSize) % textureSize

            val sx = (wrappedTx * scaleX).toInt().coerceIn(0, srcWidth - 1)
            val sy = ((textureSize - 1 - wrappedTy) * scaleY).toInt().coerceIn(0, srcHeight - 1)
            val srcIdx = sy * srcWidth + sx
            return if (srcIdx < src.size) src[srcIdx] else 0
        }


        for (dy in 0 until slotSize) {
            for (dx in 0 until slotSize) {
                if (dx in border until border + textureSize && dy in border until border + textureSize) {
                    continue
                }

                val tx = dx - border
                val ty = dy - border

                val pixel = sampleWrapped(tx, ty)
                val destIdx = (slotY + dy) * size + (slotX + dx)
                if (destIdx in pixels.indices) {
                    pixels[destIdx] = pixel
                }
            }
        }
    }

    fun upload() {
        val buffer = MemoryUtil.memAlloc(size * size * 4)
        try {
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xFF).toByte())
                buffer.put(((pixel shr 8) and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
                buffer.put(((pixel shr 24) and 0xFF).toByte())
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

class TextureAtlas(
    private val atlasSize: Int = 4096,
    private val textureSize: Int = 128,
    private val border: Int = 8
) {
    private val pages = mutableListOf<AtlasPage>()

    private val regions = ConcurrentHashMap<Int, AtlasRegion>()

    private val slotSize = textureSize + 2 * border

    val texturesPerPage: Int = (atlasSize / slotSize) * (atlasSize / slotSize)

    fun addTexture(textureId: Int, pixels: IntArray, width: Int = textureSize, height: Int = textureSize): AtlasRegion {
        regions[textureId]?.let { return it }

        var region: AtlasRegion? = null
        var pageIndex = 0

        for ((idx, page) in pages.withIndex()) {
            region = page.addTexture(pixels, width, height)
            if (region != null) {
                pageIndex = idx
                break
            }
        }

        if (region == null) {
            val newPage = AtlasPage(atlasSize, textureSize, border)
            pages.add(newPage)
            pageIndex = pages.size - 1
            region = newPage.addTexture(pixels, width, height)!!
        }

        val finalRegion = region.copy(atlasPageIndex = pageIndex)
        regions[textureId] = finalRegion

        return finalRegion
    }

    fun getRegion(textureId: Int): AtlasRegion? = regions[textureId]

    fun hasTexture(textureId: Int): Boolean = regions.containsKey(textureId)

    fun upload() {
        for (page in pages) {
            page.upload()
        }
    }

    fun bindPages(startUnit: Int = 0) {
        for ((idx, page) in pages.withIndex()) {
            glActiveTexture(GL_TEXTURE0 + startUnit + idx)
            glBindTexture(GL_TEXTURE_2D, page.glTextureId)
        }
    }

    fun bindPage(pageIndex: Int, unit: Int = 0) {
        if (pageIndex in pages.indices) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, pages[pageIndex].glTextureId)
        }
    }

    fun getPageTextureId(pageIndex: Int): Int? {
        return pages.getOrNull(pageIndex)?.glTextureId
    }

    fun getPageCount(): Int = pages.size

    fun getTextureCount(): Int = regions.size

    fun getBorder(): Int = border

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

    fun cleanup() {
        for (page in pages) {
            page.cleanup()
        }
        pages.clear()
        regions.clear()
    }

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
