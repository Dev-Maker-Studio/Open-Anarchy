package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import org.darkan.tools.cache.texture.TextureRaster

/**
 * Type 2: Horizontal gradient.
 * Returns the horizontal coordinate table directly as grayscale output.
 */
class TextureOpHorizontalGradient : TextureOperation(0, true) {
    override fun getMonochromeOutput(y: Int): IntArray {
        return TextureRaster.horizontalTable!!
    }
}
