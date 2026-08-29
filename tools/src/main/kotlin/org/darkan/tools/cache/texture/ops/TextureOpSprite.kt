package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 39: Renders a sprite as a texture.
 * The sprite is scaled to fit the texture dimensions.
 */
open class TextureOpSprite : TextureOperation(0, false) {
    /** Sprite ID to load */
    var spriteId: Int = -1
        private set

    /** Loaded sprite pixel data (ARGB format) */
    protected var spritePixels: IntArray? = null

    /** Sprite width */
    protected var spriteWidth: Int = 0

    /** Sprite height */
    protected var spriteHeight: Int = 0

    override fun clearImageCache() {
        super.clearImageCache()
        spritePixels = null
    }

    /**
     * Load sprite data for rendering.
     *
     * @param pixels ARGB pixel data
     * @param width Sprite width
     * @param height Sprite height
     */
    fun loadSprite(pixels: IntArray, width: Int, height: Int) {
        spritePixels = pixels
        spriteWidth = width
        spriteHeight = height
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> spriteId = buffer.readShort() and 0xFFFF
        }
    }

    override fun getTextureId(): Int = spriteId

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty && spritePixels != null) {
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            // Calculate source row with scaling
            val srcY = if (spriteHeight != currentHeight) {
                spriteHeight * y / currentHeight
            } else {
                y
            }
            val rowOffset = srcY * spriteWidth

            if (spriteWidth == currentWidth) {
                // Fast path: no horizontal scaling needed
                var offset = rowOffset
                for (x in 0 until currentWidth) {
                    val pixel = spritePixels!![offset++]
                    outputBlue[x] = (pixel and 0xff) shl 4
                    outputGreen[x] = (pixel and 0xff00) shr 4
                    outputRed[x] = (pixel and 0xff0000) shr 12
                }
            } else {
                // Scaled path
                for (x in 0 until currentWidth) {
                    val srcX = x * spriteWidth / currentWidth
                    val pixel = spritePixels!![rowOffset + srcX]
                    outputBlue[x] = (pixel and 0xff) shl 4
                    outputGreen[x] = (pixel and 0xff00) shr 4
                    outputRed[x] = (pixel and 0xff0000) shr 12
                }
            }
        }

        return output
    }
}
