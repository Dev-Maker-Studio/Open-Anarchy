package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.RENDER_ANIMATIONS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition

/**
 * Decodes Base Animation Set (BAS / Render Animations) definitions from the
 * CONFIGS index (2), archive 32.
 *
 * BAS definitions store movement animation sets for players and NPCs,
 * including stand, walk, run, and teleport animations with directional
 * variants and turning animations.
 */
class BASDecoder : DefinitionDecoder<BASDefinition>(Index.CONFIGS) {

    override fun create(size: Int) = Array(size) { BASDefinition(it) }

    override fun getArchive(id: Int) = RENDER_ANIMATIONS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, RENDER_ANIMATIONS)
    }

    override fun BASDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // Opcode 1: stand + walk animations (two bigSmarts)
            1 -> {
                standAnimation = buffer.readBigSmart()
                walkAnimation = buffer.readBigSmart()
            }
            // Opcode 2-9: teleport and run animations
            2 -> teleportAnimation = buffer.readBigSmart()
            3 -> teleDir3 = buffer.readBigSmart()
            4 -> teleDir2 = buffer.readBigSmart()
            5 -> teleDir1 = buffer.readBigSmart()
            6 -> runAnimation = buffer.readBigSmart()
            7 -> runDir3 = buffer.readBigSmart()
            8 -> runDir2 = buffer.readBigSmart()
            9 -> runDir1 = buffer.readBigSmart()
            // Opcode 26: modelWidth and modelLength (2 unsigned bytes, each * 4)
            26 -> {
                buffer.readUnsignedByte() // modelWidth * 4
                buffer.readUnsignedByte() // modelLength * 4
            }
            // Opcode 27: per-slot vertex transformations (slot index + 6 shorts)
            27 -> {
                buffer.readUnsignedByte() // slot index
                repeat(6) {
                    buffer.readShort() // transformation value
                }
            }
            // Opcode 28: per-slot obj visibility array
            28 -> {
                val count = buffer.readUnsignedByte()
                repeat(count) {
                    buffer.readUnsignedByte() // visibility value
                }
            }
            29 -> buffer.readUnsignedByte()  // yawAcceleration
            30 -> buffer.readUnsignedShort() // yawMaxVelocity
            31 -> buffer.readUnsignedByte()  // rollAcceleration
            32 -> buffer.readUnsignedShort() // rollMaxVelocity
            33 -> buffer.readShort()         // rollTargetAngle
            34 -> buffer.readUnsignedByte()  // pitchAcceleration
            35 -> buffer.readUnsignedShort() // pitchMaxVelocity
            36 -> buffer.readShort()         // pitchTargetAngle
            37 -> buffer.readUnsignedByte()  // unused
            // Opcodes 38-42: stand turn and walk directional animations
            38 -> standTurn1 = buffer.readBigSmart()
            39 -> standTurn2 = buffer.readBigSmart()
            40 -> walkDir3 = buffer.readBigSmart()
            41 -> walkDir2 = buffer.readBigSmart()
            42 -> walkDir1 = buffer.readBigSmart()
            43 -> buffer.readUnsignedShort() // unknown
            44 -> buffer.readUnsignedShort() // unknown
            45 -> buffer.readUnsignedShort() // iconHeightOverride
            // Opcodes 46-51: turn animations for teleport, run, and walk
            46 -> teleTurn1 = buffer.readBigSmart()
            47 -> teleTurn2 = buffer.readBigSmart()
            48 -> runTurn1 = buffer.readBigSmart()
            49 -> runTurn2 = buffer.readBigSmart()
            50 -> walkTurn1 = buffer.readBigSmart()
            51 -> walkTurn2 = buffer.readBigSmart()
            // Opcode 52: random stand sequences + chances
            52 -> {
                val count = buffer.readUnsignedByte()
                repeat(count) {
                    buffer.readBigSmart() // animation id
                    buffer.readUnsignedByte() // chance weight
                }
            }
            // Opcode 53: rendersShadow = false (boolean flag, no data bytes)
            53 -> { }
            // Opcode 54: hillRotateX and hillRotateZ (2 unsigned bytes, each << 6)
            54 -> {
                buffer.readUnsignedByte() // hillRotateX
                buffer.readUnsignedByte() // hillRotateZ
            }
            // Opcode 55: per-slot turn angle adjustment (slot + unsigned short)
            55 -> {
                buffer.readUnsignedByte() // slot index
                buffer.readUnsignedShort() // turn angle value
            }
            // Opcode 56: per-slot projection offset (slot + 3 shorts)
            56 -> {
                buffer.readUnsignedByte() // slot index
                repeat(3) {
                    buffer.readShort() // projection offset per direction
                }
            }
            else -> error("Unhandled BAS definition opcode: $opcode for def $id")
        }
    }
}
