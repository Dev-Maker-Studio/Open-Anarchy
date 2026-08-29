package org.darkan.tools.cache.texture.ops


/**
 * Type 18: Tiled sprite rendering.
 * Similar to TextureOpSprite but tiles the sprite across the texture.
 */
class TextureOpTiledSprite : TextureOpSprite() {

    override fun getColorOutput(y: Int): Array<IntArray> {
        val output = colorImageCache.getPalette(y)
        if (colorImageCache.dirty && spritePixels != null) {
            val outputRed = output[0]
            val outputGreen = output[1]
            val outputBlue = output[2]
            val tileY = y % spriteHeight * spriteHeight

            for (x in 0 until currentWidth) {
                val pixel = spritePixels!![x % spriteWidth + tileY]
                outputBlue[x] = (pixel and 0xff) shl 4
                outputGreen[x] = (pixel and 0xff00) shr 4
                outputRed[x] = (pixel and 0xff0000) shr 12
            }
        }
        return output
    }
}
