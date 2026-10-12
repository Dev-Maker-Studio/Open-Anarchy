package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class ParticleDefinition(
    override var id: Int = -1,
    var rotation: Int = 0,
    var unused2: Int? = null,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var offsetZ: Int = 0,
    var type: Int = 0,
    var sizeMultiplier: Int = 0,
    var particleHandlingType: Int = 0,
    var currentOffset: Boolean = false,
    var verticeCalculationType: Boolean = false,
    var unusedOpcode10: Boolean = false
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
}
