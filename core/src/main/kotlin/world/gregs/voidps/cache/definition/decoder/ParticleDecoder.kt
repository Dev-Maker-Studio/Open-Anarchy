package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder

class ParticleDecoder : DefinitionDecoder<ParticleDefinition>(Index.PARTICLES) {

    private val encoder = ParticleEncoder()

    override fun create(size: Int) = Array(size) { ParticleDefinition(id = it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(Index.PARTICLES, ARCHIVE)

    override fun canonicalOpcodes(definition: ParticleDefinition): IntArray = encoder.opcodes(definition)

    override fun ParticleDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> rotation = buffer.readUnsignedShort()
            2 -> unused2 = buffer.readUnsignedByte()
            3 -> {
                offsetX = buffer.readInt()
                offsetY = buffer.readInt()
                offsetZ = buffer.readInt()
            }
            4 -> {
                type = buffer.readUnsignedByte()
                sizeMultiplier = buffer.readInt()
            }
            6 -> particleHandlingType = buffer.readUnsignedByte()
            8 -> currentOffset = true
            9 -> verticeCalculationType = true
            10 -> unusedOpcode10 = true
            else -> error("Unhandled particle definition opcode: $opcode for def $id")
        }
    }

    companion object {
        const val ARCHIVE = 1
    }
}
