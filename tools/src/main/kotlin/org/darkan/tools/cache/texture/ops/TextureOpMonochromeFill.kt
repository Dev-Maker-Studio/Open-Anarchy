package org.darkan.tools.cache.texture.ops

import org.darkan.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader

class TextureOpMonochromeFill : TextureOperation(0, true) {
    private var fillValue: Int = 4096

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            for (x in 0 until currentWidth) {
                output[x] = fillValue
            }
        }
        return output
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> fillValue = (buffer.readUnsignedByte() shl 12) / 255
        }
    }
}
