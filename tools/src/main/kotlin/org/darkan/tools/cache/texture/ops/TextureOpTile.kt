package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import org.darkan.tools.cache.texture.TextureRaster
import world.gregs.voidps.buffer.read.BufferReader

/**
 * Type 20: Tiles the input texture multiple times.
 */
class TextureOpTile : TextureOperation(1, false) {
    private var tilesX: Int = 4
    private var tilesY: Int = 4

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val tileWidth = TextureRaster.currentWidth / tilesX
            val tileHeight = TextureRaster.currentHeight / tilesY
            val childOutput: IntArray
            if (tileHeight > 0) {
                val index = y % tileHeight
                childOutput = getChildMonochromeOutput(0, index * TextureRaster.currentHeight / tileHeight)
            } else {
                childOutput = getChildMonochromeOutput(0, 0)
            }

            for (x in 0 until TextureRaster.currentWidth) {
                if (tileWidth > 0) {
                    val tileX = x % tileWidth
                    output[x] = childOutput[tileX * TextureRaster.currentWidth / tileWidth]
                } else {
                    output[x] = childOutput[0]
                }
            }
        }
        return output
    }

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty) {
            val tileWidth = TextureRaster.currentWidth / tilesX
            val tileHeight = TextureRaster.currentHeight / tilesY
            val childOutput: Array<IntArray>
            if (tileHeight > 0) {
                val tileY = y % tileHeight
                childOutput = getChildColorOutput(0, tileY * TextureRaster.currentHeight / tileHeight)
            } else {
                childOutput = getChildColorOutput(0, 0)
            }

            val childRed = childOutput[0]
            val childGreen = childOutput[1]
            val childBlue = childOutput[2]
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]

            for (x in 0 until TextureRaster.currentWidth) {
                val sourceX: Int = if (tileWidth > 0) {
                    val tileX = x % tileWidth
                    tileX * TextureRaster.currentWidth / tileWidth
                } else {
                    0
                }
                outputRed[x] = childRed[sourceX]
                outputGreen[x] = childGreen[sourceX]
                outputBlue[x] = childBlue[sourceX]
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> tilesX = buffer.readUnsignedByte()
            1 -> tilesY = buffer.readUnsignedByte()
        }
    }
}
