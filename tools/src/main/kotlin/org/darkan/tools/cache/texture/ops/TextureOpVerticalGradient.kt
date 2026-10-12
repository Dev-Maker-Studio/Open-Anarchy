package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation

class TextureOpVerticalGradient : TextureOperation(0, true) {
    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val value = verticalTable[y]
            for (x in 0 until currentWidth) {
                output[x] = value
            }
        }
        return output
    }
}
