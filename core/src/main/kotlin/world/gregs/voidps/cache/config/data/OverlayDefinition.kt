package world.gregs.voidps.cache.config.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class OverlayDefinition(
    override var id: Int = -1,
    var colour: Int = 0,
    var colourRgb: Int = -1,
    var blendColourRgb: Int = -1,
    var texture: Int = -1,
    var hideUnderlay: Boolean = true,
    var blendColour: Int = -1,
    var anInt961: Int = 0,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var anInt3633: Int = DEFAULT_SLOT,
    var slot: Int = DEFAULT_SLOT,
    var underlayOverrides: Boolean = false,
    var waterColour: Int = 1190717,
    var waterScale: Int = 64,
    var waterIntensity: Int = 127
) : Definition, OpcodeOrdered {

    companion object {
        const val DEFAULT_SLOT = 8
    }

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
}