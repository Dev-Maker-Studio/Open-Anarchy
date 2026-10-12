package world.gregs.voidps.cache.definition.decoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ANIMATION_FRAMES
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition

class AnimationFrameDecoder : DefinitionDecoder<AnimationFrameDefinition>(ANIMATION_FRAMES) {

    override fun create(size: Int) = Array(size) { AnimationFrameDefinition(it) }

    override fun getArchive(id: Int) = id ushr BITS

    override fun getFile(id: Int) = id and MASK

    override fun readLoop(definition: AnimationFrameDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun AnimationFrameDefinition.read(opcode: Int, buffer: Reader) {
        header = buffer.readUnsignedByte()
        baseId = buffer.readUnsignedShort()
        val declared = buffer.readUnsignedByte()
        val count = if (declared == 0 && buffer.readableBytes() > 0) {
            AnimationFrameDefinition.WRAPPED_COUNT
        } else {
            declared
        }
        flags = IntArray(count) { buffer.readUnsignedByte() }
        var components = 0
        for (flag in flags) {
            for (bit in COMPONENTS) {
                if (flag and bit != 0) {
                    components++
                }
            }
        }
        val read = IntArray(components)
        var wide: IntArrayList? = null
        for (index in 0 until components) {
            val start = buffer.position()
            read[index] = buffer.readSignedSmart()
            if (buffer.position() - start == 2 && read[index] in NARROW) {
                (wide ?: IntArrayList().also { wide = it }).add(index)
            }
        }
        values = read
        wideValues = wide?.toIntArray()
        check(buffer.readableBytes() == 0) {
            "Animation frame $id has ${buffer.readableBytes()} bytes past its $components transform values."
        }
    }

    companion object {
        const val BITS = 16

        private const val MASK = (1 shl BITS) - 1

        private val COMPONENTS = intArrayOf(0x1, 0x2, 0x4)

        private val NARROW = -64..63
    }
}
