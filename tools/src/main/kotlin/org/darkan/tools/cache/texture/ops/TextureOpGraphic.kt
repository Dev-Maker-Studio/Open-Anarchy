package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

open class TextureOpGraphic : TextureOperation(0, false) {
    var graphicId: Int = -1
        private set

    protected var graphicPixels: IntArray? = null

    protected var graphicWidth: Int = 0

    protected var graphicHeight: Int = 0

    override fun clearImageCache() {
        super.clearImageCache()
        graphicPixels = null
    }

    fun loadGraphic(pixels: IntArray, width: Int, height: Int) {
        graphicPixels = pixels
        graphicWidth = width
        graphicHeight = height
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> graphicId = buffer.readShort() and 0xFFFF
        }
    }

    override fun getTextureId(): Int = graphicId

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)

        if (colorImageCache.dirty && graphicPixels != null) {
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            val srcY = if (graphicHeight != currentHeight) {
                graphicHeight * y / currentHeight
            } else {
                y
            }
            val rowOffset = srcY * graphicWidth

            if (graphicWidth == currentWidth) {
                var offset = rowOffset
                for (x in 0 until currentWidth) {
                    val pixel = graphicPixels!![offset++]
                    outputBlue[x] = (pixel and 0xff) shl 4
                    outputGreen[x] = (pixel and 0xff00) shr 4
                    outputRed[x] = (pixel and 0xff0000) shr 12
                }
            } else {
                for (x in 0 until currentWidth) {
                    val srcX = x * graphicWidth / currentWidth
                    val pixel = graphicPixels!![rowOffset + srcX]
                    outputBlue[x] = (pixel and 0xff) shl 4
                    outputGreen[x] = (pixel and 0xff00) shr 4
                    outputRed[x] = (pixel and 0xff0000) shr 12
                }
            }
        }

        return output
    }
}
