package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation

class TextureOpHorizontalGradient : TextureOperation(0, true) {
    override fun getMonochromeOutput(y: Int): IntArray {
        return horizontalTable
    }
}
