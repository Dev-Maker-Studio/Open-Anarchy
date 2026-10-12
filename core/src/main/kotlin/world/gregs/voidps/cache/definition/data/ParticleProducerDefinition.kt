package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class ParticleProducerDefinition(
    override var id: Int = -1,
    var minAngleHorizontal: Int = 0,
    var maxAngleHorizontal: Int = 0,
    var minAngleVertical: Int = 0,
    var maxAngleVertical: Int = 0,
    var unused2: Int? = null,
    var minSpeed: Int = 0,
    var maxSpeed: Int = 0,
    var speedUpdateType: Int = 0,
    var speedFallOffStep: Int = 0,
    var size: Int? = null,
    var minStartColour: Int = 0,
    var maxStartColour: Int = 0,
    var minLifetime: Int = 0,
    var maxLifetime: Int = 0,
    var minParticleRate: Int = 0,
    var maxParticleRate: Int = 0,
    var secondaryParticles: IntArray? = null,
    var particles: IntArray? = null,
    var lowestDisplayPlane: Int = -2,
    var highestDisplayPlane: Int = -2,
    var updatesPerCycle: Int = 0,
    var materialId: Int = -1,
    var activeFirst: Int? = null,
    var emissionEndTime: Int? = null,
    var lifetime: Int? = null,
    var periodic: Int? = null,
    var nonTexturedProducerId: Int = -1,
    var faceColourRgb: Int = 0,
    var minimumSetting: Int = 0,
    var colourFading: Int = 100,
    var alphaFading: Int = 100,
    var endSpeed: Int = -1,
    var speedChange: Int = 100,
    var uniformColourVariance: Boolean = true,
    var effectiveVertexUids: IntArray? = null,
    var unusedOpcode26: Boolean = true,
    var endSize: Int? = null,
    var sizeChange: Int = 100,
    var unused29: Int? = null,
    var textured: Boolean = false,
    var minSize: Int? = null,
    var maxSize: Int? = null,
    var adjustsLightIntensity: Boolean = true,
    var killOverlapping: Boolean = false,
    var killAboveSurface: Boolean = true
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
}
