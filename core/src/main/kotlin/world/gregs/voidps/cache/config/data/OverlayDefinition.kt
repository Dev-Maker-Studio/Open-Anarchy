package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class OverlayDefinition(
    override var id: Int = -1,
    var colour: Int = 0,
    /**
     * The packed rgb opcode 1 carried. [colour] is `rgbToHsl` of it, which is not invertible, so
     * the rgb is kept alongside for the encoder. -1 when the file had no opcode 1.
     */
    var colourRgb: Int = -1,
    /** The packed rgb opcode 7 carried; see [colourRgb]. -1 when the file had no opcode 7. */
    var blendColourRgb: Int = -1,
    var texture: Int = -1,
    var hideUnderlay: Boolean = true,
    var blendColour: Int = -1,
    var anInt961: Int = 0,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var anInt3633: Int = 8,
    var underlayOverrides: Boolean = false,
    var waterColour: Int = 1190717,
    var waterScale: Int = 64,
    var waterIntensity: Int = 127
) : Definition, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null
}