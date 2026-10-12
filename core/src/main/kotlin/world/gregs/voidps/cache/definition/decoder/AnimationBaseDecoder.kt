package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ANIMATION_SKELETONS
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition

class AnimationBaseDecoder : DefinitionDecoder<AnimationBaseDefinition>(ANIMATION_SKELETONS) {

    override fun create(size: Int) = Array(size) { AnimationBaseDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun getArchive(id: Int) = id

    override fun readLoop(definition: AnimationBaseDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun AnimationBaseDefinition.read(opcode: Int, buffer: Reader) {
        val declared = buffer.readUnsignedByte()
        val count = if (declared == 0 && buffer.readableBytes() > 0) {
            AnimationBaseDefinition.WRAPPED_COUNT
        } else {
            declared
        }
        types = IntArray(count) { buffer.readUnsignedByte() }
        shadowed = IntArray(count) { buffer.readUnsignedByte() }
        submeshes = IntArray(count) { buffer.readUnsignedShort() }
        val lengths = IntArray(count) { buffer.readUnsignedByte() }
        labels = Array(count) { transform -> IntArray(lengths[transform]) { buffer.readUnsignedByte() } }
        if (buffer.readableBytes() > 0) {
            trailing = ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        }
    }
}
