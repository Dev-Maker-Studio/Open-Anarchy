package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.encoder.ParticleProducerEncoder

class ParticleProducerDecoder : DefinitionDecoder<ParticleProducerDefinition>(Index.PARTICLES) {

    private val encoder = ParticleProducerEncoder()

    override fun create(size: Int) = Array(size) { ParticleProducerDefinition(id = it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(Index.PARTICLES, ARCHIVE)

    override fun canonicalOpcodes(definition: ParticleProducerDefinition): IntArray = encoder.opcodes(definition)

    override fun ParticleProducerDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                minAngleHorizontal = buffer.readUnsignedShort()
                maxAngleHorizontal = buffer.readUnsignedShort()
                minAngleVertical = buffer.readUnsignedShort()
                maxAngleVertical = buffer.readUnsignedShort()
            }
            2 -> unused2 = buffer.readUnsignedByte()
            3 -> {
                minSpeed = buffer.readInt()
                maxSpeed = buffer.readInt()
            }
            4 -> {
                speedUpdateType = buffer.readUnsignedByte()
                speedFallOffStep = buffer.readByte()
            }
            5 -> size = buffer.readUnsignedShort()
            6 -> {
                minStartColour = buffer.readInt()
                maxStartColour = buffer.readInt()
            }
            7 -> {
                minLifetime = buffer.readUnsignedShort()
                maxLifetime = buffer.readUnsignedShort()
            }
            8 -> {
                minParticleRate = buffer.readUnsignedShort()
                maxParticleRate = buffer.readUnsignedShort()
            }
            9 -> secondaryParticles = ids(buffer)
            10 -> particles = ids(buffer)
            12 -> lowestDisplayPlane = buffer.readByte()
            13 -> highestDisplayPlane = buffer.readByte()
            14 -> updatesPerCycle = buffer.readUnsignedShort()
            15 -> materialId = buffer.readUnsignedShort()
            16 -> {
                activeFirst = buffer.readUnsignedByte()
                emissionEndTime = buffer.readUnsignedShort()
                lifetime = buffer.readUnsignedShort()
                periodic = buffer.readUnsignedByte()
            }
            17 -> nonTexturedProducerId = buffer.readUnsignedShort()
            18 -> faceColourRgb = buffer.readInt()
            19 -> minimumSetting = buffer.readUnsignedByte()
            20 -> colourFading = buffer.readUnsignedByte()
            21 -> alphaFading = buffer.readUnsignedByte()
            22 -> endSpeed = buffer.readInt()
            23 -> speedChange = buffer.readUnsignedByte()
            24 -> uniformColourVariance = false
            25 -> effectiveVertexUids = ids(buffer)
            26 -> unusedOpcode26 = false
            27 -> endSize = buffer.readUnsignedShort()
            28 -> sizeChange = buffer.readUnsignedByte()
            29 -> unused29 = buffer.readShort()
            30 -> textured = true
            31 -> {
                minSize = buffer.readUnsignedShort()
                maxSize = buffer.readUnsignedShort()
            }
            32 -> adjustsLightIntensity = false
            33 -> killOverlapping = true
            34 -> killAboveSurface = false
            else -> error("Unhandled particle producer definition opcode: $opcode for def $id")
        }
    }

    private fun ids(buffer: Reader): IntArray {
        val count = buffer.readUnsignedByte()
        return IntArray(count) { buffer.readUnsignedShort() }
    }

    companion object {
        const val ARCHIVE = 0
    }
}
