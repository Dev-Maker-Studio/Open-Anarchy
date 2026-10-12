package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.RENDER_ANIMATIONS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.encoder.BASEncoder

class BASDecoder : DefinitionDecoder<BASDefinition>(Index.CONFIGS) {

    private val encoder = BASEncoder()

    override fun create(size: Int) = Array(size) { BASDefinition(it) }

    override fun canonicalOpcodes(definition: BASDefinition): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = RENDER_ANIMATIONS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, RENDER_ANIMATIONS)
    }

    override fun BASDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                standAnimation = buffer.readBigSmart()
                walkAnimation = buffer.readBigSmart()
            }
            2 -> teleportAnimation = buffer.readBigSmart()
            3 -> teleDir3 = buffer.readBigSmart()
            4 -> teleDir2 = buffer.readBigSmart()
            5 -> teleDir1 = buffer.readBigSmart()
            6 -> runAnimation = buffer.readBigSmart()
            7 -> runDir3 = buffer.readBigSmart()
            8 -> runDir2 = buffer.readBigSmart()
            9 -> runDir1 = buffer.readBigSmart()
            26 -> {
                modelWidth = (4 * buffer.readUnsignedByte()).toShort().toInt()
                modelLength = (4 * buffer.readUnsignedByte()).toShort().toInt()
            }
            27 -> {
                val slots = objVerticeTransformations ?: arrayOfNulls<IntArray>(BASDefinition.SLOTS).also { objVerticeTransformations = it }
                val slot = buffer.readUnsignedByte()
                slots[slot] = IntArray(6) { buffer.readShort() }
            }
            28 -> {
                val count = buffer.readUnsignedByte()
                objVisibility = IntArray(count) {
                    val value = buffer.readUnsignedByte()
                    if (value == 255) -1 else value
                }
            }
            29 -> yawAcceleration = buffer.readUnsignedByte()
            30 -> yawMaxVelocity = buffer.readUnsignedShort()
            31 -> rollAcceleration = buffer.readUnsignedByte()
            32 -> rollMaxVelocity = buffer.readUnsignedShort()
            33 -> rollTargetAngle = buffer.readShort()
            34 -> pitchAcceleration = buffer.readUnsignedByte()
            35 -> pitchMaxVelocity = buffer.readUnsignedShort()
            36 -> pitchTargetAngle = buffer.readShort()
            37 -> unused = buffer.readUnsignedByte()
            38 -> standTurn1 = buffer.readBigSmart()
            39 -> standTurn2 = buffer.readBigSmart()
            40 -> walkDir3 = buffer.readBigSmart()
            41 -> walkDir2 = buffer.readBigSmart()
            42 -> walkDir1 = buffer.readBigSmart()
            43 -> unused43 = buffer.readUnsignedShort()
            44 -> unused44 = buffer.readUnsignedShort()
            45 -> iconHeightOverride = buffer.readUnsignedShort()
            46 -> teleTurn1 = buffer.readBigSmart()
            47 -> teleTurn2 = buffer.readBigSmart()
            48 -> runTurn1 = buffer.readBigSmart()
            49 -> runTurn2 = buffer.readBigSmart()
            50 -> walkTurn1 = buffer.readBigSmart()
            51 -> walkTurn2 = buffer.readBigSmart()
            52 -> {
                val count = buffer.readUnsignedByte()
                val sequences = IntArray(count)
                val chances = IntArray(count)
                for (index in 0 until count) {
                    sequences[index] = buffer.readBigSmart()
                    chances[index] = buffer.readUnsignedByte()
                }
                randomStandSequences = sequences
                randomStandSequenceChances = chances
            }
            53 -> rendersShadow = false
            54 -> {
                hillRotateX = buffer.readUnsignedByte() shl 6
                hillRotateZ = buffer.readUnsignedByte() shl 6
            }
            55 -> {
                val slots = turnAngleAdjustment ?: IntArray(BASDefinition.SLOTS).also { turnAngleAdjustment = it }
                slots[buffer.readUnsignedByte()] = buffer.readUnsignedShort()
            }
            56 -> {
                val slots = projectionOffset ?: arrayOfNulls<IntArray>(BASDefinition.SLOTS).also { projectionOffset = it }
                val slot = buffer.readUnsignedByte()
                slots[slot] = IntArray(3) { buffer.readShort() }
            }
            else -> error("Unhandled BAS definition opcode: $opcode for def $id")
        }
    }

}
